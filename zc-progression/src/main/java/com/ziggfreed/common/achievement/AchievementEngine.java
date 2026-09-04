package com.ziggfreed.common.achievement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.achievement.event.AchievementEvents;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorRegistry;
import com.ziggfreed.common.loot.reward.RewardGrants;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.progress.DispatchOptions;
import com.ziggfreed.common.progress.ObjectiveArithmetic;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.ObjectiveIndex;
import com.ziggfreed.common.progress.ObjectiveKind;
import com.ziggfreed.common.progress.ObjectiveKindRegistry;
import com.ziggfreed.common.progress.ObjectiveProgressState;
import com.ziggfreed.common.progress.ProgressDispatchTap;
import com.ziggfreed.common.progress.StatThresholdProbe;
import com.ziggfreed.common.progress.ZoneRef;
import com.ziggfreed.common.progress.runtime.ProgressionFeedbackHook;
import com.ziggfreed.common.progress.runtime.ProgressionSystem;
import com.ziggfreed.common.progress.runtime.ProgressionSystemGate;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * A self-contained, per-consumer achievement runtime, and the PEER of the quest engine over the same
 * shared cores. Built once through {@link #builder()}, it holds the catalogue, the vocabularies it
 * is authored against, and the seams it reaches the outside world through - then answers every
 * question and performs every mutation.
 *
 * <p><b>Always on, which is the whole difference from a quest.</b> Nothing is accepted, nothing is
 * abandoned, nothing comes back around on a cooldown: every criterion of every catalogued
 * achievement is listening from the first event, and an achievement earns itself the moment its
 * criteria are all met. That is why the two engines are peers rather than one engine with a mode -
 * the lifecycle differences would otherwise thread through every method both would share.
 *
 * <p><b>The engine owns mechanics; the consumer owns everything else.</b> It knows about matching,
 * counting, earning, paying, points, milestones, and pins. It knows nothing about where state lives
 * ({@link AchievementProgressStore}), who is allowed what ({@link AchievementGates}), what a reward
 * is ({@link RewardKindRegistry}), or what any of it should be CALLED.
 *
 * <p><b>Matching is forgiving, and it is the same rule the quest engine runs</b>: an empty target
 * means match-all ("break 1000 blocks of anything") and comparison ignores case. The comparisons
 * live in {@link ObjectiveDef#matches}.
 *
 * <p><b>Threading:</b> the engine holds no per-subject state of its own, so it is as thread-safe as
 * the store behind it. Call it from whichever thread owns the subject - typically the world thread,
 * which is also where the outbound events must be fired from.
 */
public final class AchievementEngine {

    /** How many of an achievement's criteria are met, out of how many there are. */
    public record CriterionTally(int completed, int total) {

        /** Completion as a 0..1 fraction, safe when the achievement has no criteria. */
        public double fraction() {
            return total <= 0 ? 0d : (double) completed / total;
        }
    }

    private final ObjectiveKindRegistry objectiveKinds;
    private final RewardKindRegistry rewardKinds;
    private final AchievementProgressStore store;
    private final AchievementGates gates;
    /** Is the achievement system switched on for this player at all - the owner's switch, composed. */
    private final ProgressionSystemGate systemGate;
    private final ProgressDispatchTap tap;
    private final ProgressionFeedbackHook feedbackHook;
    /** Null when no factor vocabulary was wired, which switches the threshold re-check off. */
    @Nullable private final StatThresholdProbe statProbe;
    @Nullable private final BiConsumer<Subject, String> rewardRetryQueue;
    private final Consumer<String> warn;
    private final LongSupplier clock;
    private final int maxPinned;
    private final boolean nativeEvents;
    /** Swapped whole by {@link #setMilestones}, exactly as the catalogue is by {@link #setAchievements}. */
    private volatile List<AchievementMilestone> milestones;

    /** The catalogue and its index, replaced together so a dispatch never sees a half-loaded pair. */
    private volatile Map<String, Achievement> achievements = Map.of();
    private volatile ObjectiveIndex index = ObjectiveIndex.EMPTY;
    /** child id -> the meta achievements waiting on it, so a cascade is a lookup rather than a scan. */
    private volatile Map<String, List<String>> metaParents = Map.of();

    /** Authoring mistakes that would otherwise repeat on every event, reported once per case. */
    private final Set<String> warnedOnce = ConcurrentHashMap.newKeySet();

    private AchievementEngine(@Nonnull Builder b) {
        this.objectiveKinds = b.objectiveKinds != null ? b.objectiveKinds : new ObjectiveKindRegistry();
        this.rewardKinds = b.rewardKinds != null ? b.rewardKinds : new RewardKindRegistry();
        this.store = b.store != null ? b.store : new InMemoryAchievementProgressStore();
        this.gates = b.gates;
        this.systemGate = b.systemGate;
        this.tap = b.tap;
        this.feedbackHook = b.feedbackHook;
        this.statProbe = StatThresholdProbe.of(b.factors, b.factorContext, b.warn);
        this.rewardRetryQueue = b.rewardRetryQueue;
        this.warn = b.warn;
        this.clock = b.clock;
        this.maxPinned = b.maxPinned;
        this.nativeEvents = b.nativeEvents;
        this.milestones = List.copyOf(b.milestones);
    }

    @Nonnull
    public static Builder builder() {
        return new Builder();
    }

    // ==================== Wiring accessors ====================

    /** The objective vocabulary this engine was built with (a validator reads it). */
    @Nonnull
    public ObjectiveKindRegistry objectiveKinds() {
        return objectiveKinds;
    }

    /** The reward vocabulary this engine was built with (a validator reads it). */
    @Nonnull
    public RewardKindRegistry rewardKinds() {
        return rewardKinds;
    }

    /** The persistence seam, for a consumer that needs to reach it directly. */
    @Nonnull
    public AchievementProgressStore store() {
        return store;
    }

    /** How many achievements a subject may pin at once. */
    public int maxPinned() {
        return maxPinned;
    }

    /** The points milestones this engine is running, lowest threshold first. */
    @Nonnull
    public List<AchievementMilestone> milestones() {
        return milestones;
    }

    /**
     * Replace the points milestones, sorted lowest threshold first. The exact peer of
     * {@link #setAchievements}: a consumer whose milestones arrive with its content publishes them
     * here rather than rebuilding the engine, which would orphan every reference anybody is holding.
     */
    public void setMilestones(@Nonnull List<AchievementMilestone> replacement) {
        List<AchievementMilestone> sorted = new ArrayList<>(replacement);
        sorted.sort(Comparator.comparingInt(AchievementMilestone::threshold));
        this.milestones = List.copyOf(sorted);
    }

    /** The engine's clock, in epoch milliseconds. Injected, so unlock instants are testable. */
    public long now() {
        return clock.getAsLong();
    }

    // ==================== Content ====================

    /**
     * Replace the whole catalogue and rebuild the dispatch index. All three views are swapped
     * together, so a dispatch running concurrently sees either the old set or the new one.
     */
    public void setAchievements(@Nonnull Collection<Achievement> catalogue) {
        Map<String, Achievement> byId = new LinkedHashMap<>();
        for (Achievement achievement : catalogue) {
            byId.put(achievement.id(), achievement);
        }
        Map<String, Achievement> frozen = Map.copyOf(byId);

        Map<String, List<String>> parents = new LinkedHashMap<>();
        for (Achievement achievement : frozen.values()) {
            for (String child : achievement.metaChildren()) {
                parents.computeIfAbsent(child, key -> new ArrayList<>()).add(achievement.id());
            }
        }
        Map<String, List<String>> frozenParents = new LinkedHashMap<>();
        parents.forEach((child, list) -> frozenParents.put(child, List.copyOf(list)));

        this.achievements = frozen;
        this.index = ObjectiveIndex.of(frozen.values(), Achievement::id, Achievement::criteria);
        this.metaParents = Map.copyOf(frozenParents);
        this.warnedOnce.clear();
    }

    /** The achievement with this id, or null. */
    @Nullable
    public Achievement achievement(@Nullable String achievementId) {
        return achievementId == null ? null : achievements.get(achievementId);
    }

    /** The whole catalogue. */
    @Nonnull
    public Collection<Achievement> achievements() {
        return achievements.values();
    }

    /** The kind-to-criteria index a dispatch walks; a producer can ask what is worth firing. */
    @Nonnull
    public ObjectiveIndex index() {
        return index;
    }

    // ==================== Status ====================

    /** Where this achievement stands for this subject. An unknown id reads LOCKED. */
    @Nonnull
    public AchievementStatus status(@Nonnull Subject subject, @Nonnull String achievementId) {
        return store.status(subject, achievementId);
    }

    /** Has this subject earned it (whether or not they have collected)? */
    public boolean isUnlocked(@Nonnull Subject subject, @Nonnull String achievementId) {
        return store.status(subject, achievementId).isUnlocked();
    }

    /** Has this subject earned it AND taken everything it pays? */
    public boolean isClaimed(@Nonnull Subject subject, @Nonnull String achievementId) {
        return store.status(subject, achievementId).isClaimed();
    }

    /** When this subject earned it, in epoch milliseconds, or {@code 0}. */
    public long unlockedAt(@Nonnull Subject subject, @Nonnull String achievementId) {
        return store.unlockedAt(subject, achievementId);
    }

    /**
     * Should this achievement be listed for this subject? One already earned always is - somebody
     * must be able to see what they have. Otherwise it must be in circulation, not hidden, and past
     * whatever the consumer gates the sight of it behind.
     */
    public boolean isVisible(@Nonnull Subject subject, @Nonnull Achievement achievement) {
        if (store.status(subject, achievement.id()).isUnlocked()) {
            return true;
        }
        if (!achievement.available() || achievement.hidden()) {
            return false;
        }
        // Hidden-until-qualified: canProgress IS the Requires answer, so the two can never drift.
        if (achievement.requirePrerequisites()
                && !safeGate(() -> gates.canProgress(subject, achievement), "canProgress", achievement)) {
            return false;
        }
        return safeGate(() -> gates.visible(subject, achievement), "visible", achievement);
    }

    // ==================== Progress ====================

    /** This subject's progress on one criterion, by its position in the achievement's list. */
    @Nonnull
    public ObjectiveProgressState progressOf(@Nonnull Subject subject, @Nonnull Achievement achievement,
                                             int criterionIndex) {
        ObjectiveDef criterion = achievement.criterion(criterionIndex);
        if (criterion == null) {
            return new ObjectiveProgressState(0, 1);
        }
        return progressOf(subject, achievement, criterion);
    }

    /** This subject's progress on one criterion; progress is stored under the criterion's id. */
    @Nonnull
    public ObjectiveProgressState progressOf(@Nonnull Subject subject, @Nonnull Achievement achievement,
                                             @Nonnull ObjectiveDef criterion) {
        long current = store.criterionProgress(subject, achievement.id(), criterion.id());
        return ObjectiveArithmetic.stored(objectiveKinds.kind(criterion.kind()), criterion, current);
    }

    /** This subject's progress on every criterion, in criterion order. */
    @Nonnull
    public List<ObjectiveProgressState> progressOf(@Nonnull Subject subject,
                                                   @Nonnull Achievement achievement) {
        List<ObjectiveProgressState> out = new ArrayList<>(achievement.criteria().size());
        for (int i = 0; i < achievement.criteria().size(); i++) {
            out.add(progressOf(subject, achievement, i));
        }
        return out;
    }

    /** Are all of this achievement's criteria met for this subject? */
    public boolean allCriteriaComplete(@Nonnull Subject subject, @Nonnull Achievement achievement) {
        if (achievement.isMeta()) {
            return metaChildrenComplete(subject, achievement);
        }
        if (achievement.criteria().isEmpty()) {
            return false;
        }
        for (int i = 0; i < achievement.criteria().size(); i++) {
            if (!progressOf(subject, achievement, i).isCompleted()) {
                return false;
            }
        }
        return true;
    }

    /** Are every one of a meta achievement's children earned? */
    private boolean metaChildrenComplete(@Nonnull Subject subject, @Nonnull Achievement achievement) {
        for (String child : achievement.metaChildren()) {
            if (!store.status(subject, child).isUnlocked()) {
                return false;
            }
        }
        return !achievement.metaChildren().isEmpty();
    }

    /** How many criteria are met. Total is at least 1, so a bar can divide by it. */
    @Nonnull
    public CriterionTally tally(@Nonnull Subject subject, @Nonnull Achievement achievement) {
        if (achievement.isMeta()) {
            int done = 0;
            for (String child : achievement.metaChildren()) {
                if (store.status(subject, child).isUnlocked()) {
                    done++;
                }
            }
            return new CriterionTally(done, Math.max(1, achievement.metaChildren().size()));
        }
        int completed = 0;
        for (int i = 0; i < achievement.criteria().size(); i++) {
            if (progressOf(subject, achievement, i).isCompleted()) {
                completed++;
            }
        }
        return new CriterionTally(completed, Math.max(1, achievement.criteria().size()));
    }

    // ==================== Dispatch ====================

    /** {@link #dispatch} with {@link DispatchOptions#FULL} and no zone. */
    public void dispatch(@Nonnull Subject subject, @Nonnull String kindId, @Nonnull String target,
                         @Nullable String qualifier, long amount) {
        dispatch(subject, kindId, target, qualifier, amount, null, DispatchOptions.FULL);
    }

    /**
     * Feed one progress event to this subject's achievements: advance every matching criterion of
     * every achievement they have not earned yet, and earn any whose criteria that completes.
     *
     * <p>Order of business, and each step is there for a reason:
     * <ol>
     *   <li>the tap fires FIRST, before any criterion is touched, so a counter records the action
     *   even when nothing was listening for it;
     *   <li>the index narrows the work to criteria authored against this kind;
     *   <li>an achievement already earned, out of circulation, or gated shut is skipped;
     *   <li>target, qualifier, and zone must all match;
     *   <li>the registered kind decides whether the amount is added or treated as a current value.
     * </ol>
     */
    public void dispatch(@Nonnull Subject subject, @Nonnull String kindId, @Nonnull String target,
                         @Nullable String qualifier, long amount, @Nullable ZoneRef zone,
                         @Nonnull DispatchOptions options) {
        if (options.tapObservers()) {
            try {
                tap.observe(subject, kindId, target, qualifier, amount, zone);
            } catch (Throwable t) {
                warn.accept("progress tap failed for '" + kindId + "': " + t.getMessage());
            }
        }

        List<ObjectiveIndex.Entry> entries = index.forKind(kindId);
        if (entries.isEmpty()) {
            return;
        }
        ObjectiveKind kind = objectiveKinds.kind(kindId);
        if (kind == null) {
            warnOnce("kind:" + kindId, "Objective kind '" + kindId + "' is authored as a criterion but is"
                    + " not part of this engine's vocabulary - those criteria can never progress");
            return;
        }

        boolean changed = false;
        List<Achievement> earned = new ArrayList<>();
        for (ObjectiveIndex.Entry entry : entries) {
            ObjectiveDef criterion = entry.objective();
            // A follow-up fire under an additional id must not re-count a match-all criterion.
            if (options.targetedOnly() && criterion.target().isBlank()) {
                continue;
            }
            Achievement achievement = achievements.get(entry.ownerId());
            if (achievement == null || !achievement.available()) {
                continue;
            }
            if (store.status(subject, achievement.id()).isUnlocked()) {
                continue;
            }
            if (!criterion.matches(target, qualifier) || !criterion.matchesZone(zone)) {
                continue;
            }
            if (!safeGate(() -> gates.canProgress(subject, achievement), "canProgress", achievement)) {
                continue;
            }

            ObjectiveProgressState state = progressOf(subject, achievement, criterion);
            if (state.isCompleted()) {
                continue;
            }
            int before = state.current();
            boolean justCompleted = ObjectiveArithmetic.apply(kind, criterion, state, amount);
            if (state.current() == before && !justCompleted) {
                continue;
            }
            store.setCriterionProgress(subject, achievement.id(), criterion.id(), state.current());
            changed = true;
            fireProgressed(achievement, criterion.id(), subject, state, justCompleted);
            if (justCompleted && allCriteriaComplete(subject, achievement)) {
                earned.add(achievement);
            }
        }
        if (changed) {
            store.markDirty(subject);
        }
        for (Achievement achievement : earned) {
            unlock(subject, achievement, UnlockOccasion.JUST_MET);
        }
    }

    // ==================== Earning and collecting ====================

    /**
     * Earn an achievement for this subject: record it, pay whatever lands immediately, cascade to any
     * meta achievement now complete, and re-check the points milestones.
     *
     * <p>Does NOT check the criteria - a caller that wants that calls {@link #allCriteriaComplete}
     * first, and a scripted grant deliberately does not. It DOES ask the consumer's
     * {@link AchievementGates#canUnlock}, which is where a claim only one subject can win is settled.
     *
     * <p>Earned on a {@link UnlockOccasion#STANDING} occasion, which is what a caller outside the
     * engine's own dispatch is doing: the criteria are already met and something is acting on that.
     *
     * @return true when this call earned it
     */
    public boolean unlock(@Nonnull Subject subject, @Nonnull Achievement achievement) {
        return unlock(subject, achievement, UnlockOccasion.STANDING);
    }

    /**
     * Earn it, saying whether the criteria were met in this very moment or whether a standing state
     * is being acted on. The gates see the occasion and the decision is theirs either way; what it
     * buys is a refusal that knows whether it is news (see {@link UnlockOccasion}).
     *
     * @return true when this call earned it
     */
    public boolean unlock(@Nonnull Subject subject, @Nonnull Achievement achievement,
            @Nonnull UnlockOccasion occasion) {
        if (store.status(subject, achievement.id()).isUnlocked()) {
            return false;
        }
        if (!safeGate(() -> gates.canUnlock(subject, achievement, occasion), "canUnlock", achievement)) {
            return false;
        }
        store.setStatus(subject, achievement.id(), AchievementStatus.UNLOCKED);
        store.setUnlockedAt(subject, achievement.id(), now());
        // A pin marks something being worked toward, so an earned achievement gives its slot back.
        store.clearPin(subject, achievement.id());

        fireUnlocked(achievement, subject, achievement.requiresClaim());

        RewardGrants.GrantOutcome outcome = grant(subject, achievement, achievement.autoRewards());
        if (!achievement.requiresClaim()) {
            store.setStatus(subject, achievement.id(), AchievementStatus.CLAIMED);
            fireClaimed(achievement, subject, outcome, achievement.autoRewards(), false);
        }
        // Reported as a change, NOT committed. Earning is something the engine decides rather than
        // something the subject asked for, and it arrives in bulk: a self-heal walks the whole
        // catalogue, cascadeMeta walks a chain of metas off one earn, and a dispatch can earn
        // several at once. A commit per earn turns a login into one write per achievement the
        // subject already has. Collecting is the boundary, and it commits.
        store.markDirty(subject);

        cascadeMeta(subject, achievement.id(), occasion);
        checkMilestones(subject);
        return true;
    }

    /**
     * Collect what an earned achievement still owes. Refuses unless it really is waiting and the
     * consumer says the subject can receive them.
     *
     * @return true when the rewards were paid
     */
    public boolean claim(@Nonnull Subject subject, @Nonnull Achievement achievement) {
        if (store.status(subject, achievement.id()) != AchievementStatus.UNLOCKED) {
            return false;
        }
        if (!safeGate(() -> gates.canReceiveRewards(subject, achievement), "canReceiveRewards", achievement)) {
            return false;
        }
        RewardGrants.GrantOutcome outcome = grant(subject, achievement, achievement.claimRewards());
        store.setStatus(subject, achievement.id(), AchievementStatus.CLAIMED);
        // Collecting is a subject-owned transaction boundary, so the writes are committed here.
        store.markDirty(subject);
        store.flush(subject);
        fireClaimed(achievement, subject, outcome, achievement.claimRewards(), true);
        return true;
    }

    /**
     * Take an achievement back off a subject: its state, progress, unlock instant, and pin all go,
     * and any meta achievement that only stood on it is taken back too.
     *
     * @return true when there was something to take back
     */
    public boolean revoke(@Nonnull Subject subject, @Nonnull String achievementId) {
        if (!store.status(subject, achievementId).isUnlocked()
                && !hasAnyCriterionProgress(subject, achievementId)) {
            return false;
        }
        store.clearAchievement(subject, achievementId);
        for (String parentId : metaParents.getOrDefault(achievementId, List.of())) {
            if (store.status(subject, parentId).isUnlocked()) {
                store.clearAchievement(subject, parentId);
            }
        }
        store.markDirty(subject);
        return true;
    }

    /** Any recorded progress on any criterion of this achievement (or its bare legacy key)? */
    private boolean hasAnyCriterionProgress(@Nonnull Subject subject, @Nonnull String achievementId) {
        String prefix = achievementId + AchievementProgressStore.CRITERION_SEPARATOR;
        for (String key : store.progressKeys(subject)) {
            if (key.equals(achievementId) || key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Take EVERYTHING back off a subject: every achievement's state, progress, unlock instant and
     * pin, and every points milestone. For a tester re-running a session end to end, or an
     * administrator starting somebody over. Reported as one change; nothing here is a payout, so
     * nothing commits.
     *
     * <p>Whether a {@link Achievement#serverFirst() server-first} claim this subject WON is released
     * is not decided here. The {@link FirstClaimStore} seam records who won and offers no release,
     * so whoever installed a durable table releases what it recorded, on its own terms; the
     * library's boot-lifetime default forgets everything at restart anyway.
     *
     * @return how many achievement ids and milestones had a record to wipe
     */
    public int resetAll(@Nonnull Subject subject) {
        int wiped = store.knownAchievementIds(subject).size() + store.knownMilestones(subject).size();
        store.clearAll(subject);
        store.markDirty(subject);
        return wiped;
    }

    /**
     * Earn every meta achievement that was waiting on {@code childId} and is now complete, then keep
     * going for a meta standing on that meta. Bounded by the catalogue size, so a cycle in authored
     * content cannot spin here.
     *
     * <p>Carries the occasion of the earn that set it off: a meta completed by a child earned in
     * this very moment is itself being met in this very moment, and one picked up by a sweep is not.
     */
    private void cascadeMeta(@Nonnull Subject subject, @Nonnull String childId,
            @Nonnull UnlockOccasion occasion) {
        List<String> queue = new ArrayList<>(metaParents.getOrDefault(childId, List.of()));
        int guard = achievements.size() + 1;
        while (!queue.isEmpty() && guard-- > 0) {
            String parentId = queue.remove(0);
            Achievement parent = achievements.get(parentId);
            if (parent == null || !parent.available()) {
                continue;
            }
            if (store.status(subject, parentId).isUnlocked() || !metaChildrenComplete(subject, parent)) {
                continue;
            }
            if (unlock(subject, parent, occasion)) {
                queue.addAll(metaParents.getOrDefault(parentId, List.of()));
            }
        }
    }

    // ==================== Points and milestones ====================

    /** This subject's points total: every earned achievement whose points count. */
    public int points(@Nonnull Subject subject) {
        int total = 0;
        for (Achievement achievement : achievements.values()) {
            if (achievement.countsTowardTotal() && store.status(subject, achievement.id()).isUnlocked()) {
                total += achievement.points();
            }
        }
        return total;
    }

    /** Every point this subject could still earn: what is in circulation and not yet theirs. */
    public int pointsAvailable(@Nonnull Subject subject) {
        int total = 0;
        for (Achievement achievement : achievements.values()) {
            if (achievement.countsTowardTotal() && achievement.available()
                    && !store.status(subject, achievement.id()).isUnlocked()) {
                total += achievement.points();
            }
        }
        return total;
    }

    /**
     * Reach every milestone this subject's total now covers. Idempotent: a milestone already reached
     * is left alone, so calling it after any change (or on login) is safe.
     *
     * @return how many milestones this call reached
     */
    public int checkMilestones(@Nonnull Subject subject) {
        if (milestones.isEmpty()) {
            return 0;
        }
        int total = points(subject);
        int reached = 0;
        for (AchievementMilestone milestone : milestones) {
            if (milestone.threshold() > total
                    || store.milestoneStatus(subject, milestone.threshold()).isUnlocked()) {
                continue;
            }
            store.setMilestoneStatus(subject, milestone.threshold(), AchievementStatus.UNLOCKED);
            grantMilestone(subject, milestone, milestone.autoRewards());
            if (!milestone.requiresClaim()) {
                store.setMilestoneStatus(subject, milestone.threshold(), AchievementStatus.CLAIMED);
            }
            reached++;
        }
        if (reached > 0) {
            // Reported, not committed, for the same reason earning is: this is idempotent
            // maintenance that runs on login and after every earn, so a commit here rides along
            // with each of those. Collecting a milestone is the boundary.
            store.markDirty(subject);
        }
        return reached;
    }

    /** Where a milestone stands for this subject. */
    @Nonnull
    public AchievementStatus milestoneStatus(@Nonnull Subject subject, int threshold) {
        return store.milestoneStatus(subject, threshold);
    }

    /**
     * Collect what a reached milestone still owes.
     *
     * @return true when the rewards were paid
     */
    public boolean claimMilestone(@Nonnull Subject subject, int threshold) {
        AchievementMilestone milestone = milestone(threshold);
        if (milestone == null || store.milestoneStatus(subject, threshold) != AchievementStatus.UNLOCKED) {
            return false;
        }
        grantMilestone(subject, milestone, milestone.claimRewards());
        store.setMilestoneStatus(subject, threshold, AchievementStatus.CLAIMED);
        // Collecting is a subject-owned transaction boundary, so the writes are committed here.
        store.markDirty(subject);
        store.flush(subject);
        return true;
    }

    /** The milestone at this exact threshold, or null. */
    @Nullable
    public AchievementMilestone milestone(int threshold) {
        for (AchievementMilestone milestone : milestones) {
            if (milestone.threshold() == threshold) {
                return milestone;
            }
        }
        return null;
    }

    // ==================== Pinning ====================

    /**
     * Pin an achievement to the subject's tracker. Dead pins are reclaimed first, so the cap is
     * measured against live ones and a subject looking at two pins is never told they are full.
     *
     * @return false when the id is unknown, it is already earned, or they are at the cap
     */
    public boolean pin(@Nonnull Subject subject, @Nonnull String achievementId) {
        if (!achievements.containsKey(achievementId)
                || store.status(subject, achievementId).isUnlocked()) {
            return false;
        }
        prunePins(subject);
        Map<String, Long> pins = store.pins(subject);
        if (!pins.containsKey(achievementId) && pins.size() >= maxPinned) {
            return false;
        }
        store.setPin(subject, achievementId, now());
        store.markDirty(subject);
        return true;
    }

    /**
     * Unpin. Returns true when a pin was actually there.
     *
     * <p>Marked dirty exactly as {@link #pin} is: a pin is a display preference rather than
     * progress, but it is SAVED state, and a store that persists one half of a pair and not the
     * other hands the player back a pin they took off.
     */
    public boolean unpin(@Nonnull Subject subject, @Nonnull String achievementId) {
        if (!store.clearPin(subject, achievementId)) {
            return false;
        }
        store.markDirty(subject);
        return true;
    }

    /** The subject's pinned ids, oldest pin first, capped at {@link #maxPinned()}. */
    @Nonnull
    public List<String> pinned(@Nonnull Subject subject) {
        List<Map.Entry<String, Long>> pins = new ArrayList<>(store.pins(subject).entrySet());
        pins.sort(Comparator.comparingLong(Map.Entry::getValue));
        List<String> out = new ArrayList<>(pins.size());
        for (Map.Entry<String, Long> pin : pins) {
            out.add(pin.getKey());
            if (out.size() >= maxPinned) {
                break;
            }
        }
        return out;
    }

    /**
     * Drop pins for achievements that are earned or no longer catalogued. A pin marks something being
     * worked toward, so one that no longer can be is dead weight - and enough of them fill every slot
     * while the tracker looks empty.
     *
     * @return how many were dropped
     */
    public int prunePins(@Nonnull Subject subject) {
        int dropped = 0;
        for (String achievementId : store.pins(subject).keySet()) {
            boolean dead = !achievements.containsKey(achievementId)
                    || store.status(subject, achievementId).isUnlocked();
            if (dead && store.clearPin(subject, achievementId)) {
                dropped++;
            }
        }
        if (dropped > 0) {
            store.markDirty(subject);
        }
        return dropped;
    }

    // ==================== Maintenance ====================

    /**
     * Idempotent per-subject maintenance, safe to run on login and whenever an achievement surface
     * opens: dead pins are reclaimed, anything whose criteria are already met but which was never
     * earned is earned, and the milestones are re-checked.
     *
     * <p>It is also where every outstanding threshold criterion is re-read (see
     * {@link #refreshStatThresholds}), which runs FIRST so anything it completes is earned by the
     * sweep below in the same pass.
     *
     * <p>Deliberately non-destructive: an achievement whose definition has gone is LEFT alone (it may
     * come back), and nothing already earned is ever revisited.
     *
     * <p><b>Skipped outright for a player the owner's system switch has achievements OFF for.</b>
     * The threshold re-read below reads live standing values and the sweep after it EARNS, so on a
     * server with achievements switched off a login would otherwise hand out every level-shaped
     * achievement the player already qualifies for, rewards and all - progress the switch exists to
     * refuse, arriving by the one path a produced moment never takes.
     *
     * @return how many entries changed
     */
    public int selfHeal(@Nonnull Subject subject) {
        if (!systemGate.enabled(ProgressionSystem.ACHIEVEMENT, subject)) {
            return 0;
        }
        int changed = prunePins(subject);
        changed += refreshStatThresholds(subject);
        for (Achievement achievement : List.copyOf(achievements.values())) {
            if (store.status(subject, achievement.id()).isUnlocked() || !achievement.available()) {
                continue;
            }
            if (allCriteriaComplete(subject, achievement) && unlock(subject, achievement)) {
                changed++;
            }
        }
        changed += checkMilestones(subject);
        if (changed > 0) {
            store.markDirty(subject);
        }
        return changed;
    }

    // ==================== Standing-value criteria ====================

    /**
     * Re-read every outstanding {@link ObjectiveKindRegistry#STAT_THRESHOLD} criterion in the
     * catalogue for this subject and apply each reading as a high-water value. A no-op when no
     * factor vocabulary was wired, which leaves the kind purely consumer-fired.
     *
     * <p>A threshold criterion describes a state rather than a moment, so nothing may ever fire to
     * say it was met - hence the engine asking for itself.
     *
     * <p><b>Why this runs at self-heal ONLY, unlike the quest engine's</b>, which also re-reads off
     * the back of a dispatch that moved the same quest. A quest engine's re-check is bounded by the
     * handful of quests one player is CARRYING; here nothing is accepted, so the equivalent
     * piggyback would re-read part of the WHOLE catalogue on every progressing event of every
     * player, forever, to catch a value that will still be there at the next self-heal. Self-heal
     * runs on login and whenever an achievement surface opens, which is every moment the answer is
     * about to be looked at. A consumer that wants it sooner fires the kind itself, exactly as it
     * would for any other.
     *
     * @return how many criteria this call advanced
     */
    public int refreshStatThresholds(@Nonnull Subject subject) {
        if (statProbe == null) {
            return 0;
        }
        int advanced = 0;
        for (Achievement achievement : List.copyOf(achievements.values())) {
            if (!achievement.available() || store.status(subject, achievement.id()).isUnlocked()
                    || !hasStatThreshold(achievement)) {
                continue;
            }
            if (!safeGate(() -> gates.canProgress(subject, achievement), "canProgress", achievement)) {
                continue;
            }
            advanced += refreshStatThresholds(subject, achievement);
        }
        if (advanced > 0) {
            store.markDirty(subject);
        }
        return advanced;
    }

    /** One achievement's threshold criteria. Only ever reached with the probe wired and the gate open. */
    private int refreshStatThresholds(@Nonnull Subject subject, @Nonnull Achievement achievement) {
        int advanced = 0;
        for (ObjectiveDef criterion : achievement.criteria()) {
            if (!StatThresholdProbe.isStatThreshold(criterion)) {
                continue;
            }
            ObjectiveProgressState state = progressOf(subject, achievement, criterion);
            if (state.isCompleted()) {
                continue;
            }
            int before = state.current();
            boolean justCompleted = ObjectiveArithmetic.applyStanding(objectiveKinds.kind(criterion.kind()),
                    criterion, state, statProbe.valueFor(subject, criterion));
            if (state.current() == before && !justCompleted) {
                continue;
            }
            store.setCriterionProgress(subject, achievement.id(), criterion.id(), state.current());
            advanced++;
            fireProgressed(achievement, criterion.id(), subject, state, justCompleted);
        }
        return advanced;
    }

    /** Does anything here need a stat channel read at all? Answered before the gate is asked. */
    private static boolean hasStatThreshold(@Nonnull Achievement achievement) {
        for (ObjectiveDef criterion : achievement.criteria()) {
            if (StatThresholdProbe.isStatThreshold(criterion)) {
                return true;
            }
        }
        return false;
    }

    // ==================== Internals ====================

    @Nonnull
    private RewardGrants.GrantOutcome grant(@Nonnull Subject subject, @Nonnull Achievement achievement,
                                            @Nonnull List<RewardSpec> rewards) {
        return RewardGrants.grantAll(rewards, subject, "achievement:" + achievement.id(), rewardKinds,
                rewardRetryQueue, warn);
    }

    private void grantMilestone(@Nonnull Subject subject, @Nonnull AchievementMilestone milestone,
                                @Nonnull List<RewardSpec> rewards) {
        RewardGrants.grantAll(rewards, subject, "milestone:" + milestone.threshold(), rewardKinds,
                rewardRetryQueue, warn);
    }

    /** Ask a consumer gate, treating a throw as a refusal so a broken gate cannot open one. */
    private boolean safeGate(@Nonnull BooleanSupplier gate, @Nonnull String name,
                             @Nonnull Achievement achievement) {
        try {
            return gate.getAsBoolean();
        } catch (Throwable t) {
            warnOnce("gate:" + name + ":" + achievement.id(), "the " + name + " gate failed for '"
                    + achievement.id() + "', so it is treated as a refusal: " + t.getMessage());
            return false;
        }
    }

    private void fireProgressed(@Nonnull Achievement achievement, @Nonnull String criterionId,
                                @Nonnull Subject subject, @Nonnull ObjectiveProgressState state,
                                boolean justCompleted) {
        if (nativeEvents) {
            AchievementEvents.fireProgressed(achievement.id(), criterionId, subject.id(),
                    state.current(), state.required(), justCompleted, achievement.tags());
        }
    }

    /**
     * It is EARNED. The icon travels with the moment under the fixed key {@code icon}, because it is
     * the achievement's own - written onto the definition when the catalogue was folded, so nothing
     * downstream has to go looking for one - and so does everything else the fold attached under
     * {@link Achievement#momentArgs()}, beneath the engine's own names. What the earn pays on the
     * spot rides under {@code rewards}, deferred so a moment nobody authored never composes it.
     */
    private void fireUnlocked(@Nonnull Achievement achievement, @Nonnull Subject subject,
                              boolean awaitingClaim) {
        if (nativeEvents) {
            AchievementEvents.fireUnlocked(achievement.id(), subject.id(), achievement.points(),
                    awaitingClaim, achievement.tags());
        }
        ProgressionFeedbackHook.fire(feedbackHook, warn, "Achievement_Unlocked", subject,
                achievement.momentArgs(),
                "achievement", achievement.id(), "title", achievement.text().titleOr(achievement.id()),
                "icon", achievement.icon(),
                "points", Integer.valueOf(achievement.points()),
                "awaiting_claim", Boolean.valueOf(awaitingClaim),
                "rewards", (Supplier<?>) achievement::autoRewards);
    }

    /**
     * The rewards were paid, either as it was earned or when the subject came to collect them;
     * {@code collected} tells the two apart, so a jingle authored for collecting does not also
     * play over the unlock jingle of one that settled in the same breath. The list this grant
     * actually paid rides under {@code rewards} - the auto rewards when it settled as it was
     * earned, the claim rewards when the subject came to collect - deferred so a moment nobody
     * authored never composes it.
     */
    private void fireClaimed(@Nonnull Achievement achievement, @Nonnull Subject subject,
                             @Nonnull RewardGrants.GrantOutcome outcome,
                             @Nonnull List<RewardSpec> rewards, boolean collected) {
        if (nativeEvents) {
            AchievementEvents.fireClaimed(achievement.id(), subject.id(), outcome.granted(),
                    outcome.queued(), outcome.failed(), achievement.tags());
        }
        ProgressionFeedbackHook.fire(feedbackHook, warn, "Achievement_Claimed", subject,
                achievement.momentArgs(),
                "achievement", achievement.id(), "title", achievement.text().titleOr(achievement.id()),
                "icon", achievement.icon(),
                "points", Integer.valueOf(achievement.points()),
                "collected", Boolean.valueOf(collected),
                "granted", Integer.valueOf(outcome.granted()),
                "queued", Integer.valueOf(outcome.queued()),
                "failed", Integer.valueOf(outcome.failed()),
                "rewards", (Supplier<?>) () -> rewards);
    }

    /** Report an authoring mistake that would otherwise repeat on every event exactly once. */
    private void warnOnce(@Nonnull String key, @Nonnull String message) {
        if (warnedOnce.add(key)) {
            warn.accept(message);
        }
    }

    // ==================== Builder ====================

    /**
     * Assembles an {@link AchievementEngine}. Every seam has a working default, so the smallest
     * useful engine is {@code AchievementEngine.builder().build()} - an in-memory store, the built-in
     * objective vocabulary, no rewards, no gates, no milestones.
     */
    public static final class Builder {

        @Nullable private ObjectiveKindRegistry objectiveKinds;
        @Nullable private RewardKindRegistry rewardKinds;
        @Nullable private AchievementProgressStore store;
        private AchievementGates gates = AchievementGates.OPEN;
        private ProgressionSystemGate systemGate = ProgressionSystemGate.OPEN;
        private ProgressDispatchTap tap = ProgressDispatchTap.NONE;
        @Nullable private FactorRegistry factors;
        @Nullable private Function<Subject, FactorContext> factorContext;
        @Nullable private BiConsumer<Subject, String> rewardRetryQueue;
        private ProgressionFeedbackHook feedbackHook = ProgressionFeedbackHook.NONE;
        private Consumer<String> warn = DEFAULT_WARN;
        private LongSupplier clock = System::currentTimeMillis;
        private int maxPinned = 5;
        private boolean nativeEvents = true;
        private final List<AchievementMilestone> milestones = new ArrayList<>();

        Builder() {
        }

        /** The objective vocabulary content is authored against. Defaults to the built-ins alone. */
        @Nonnull
        public Builder objectiveKinds(@Nullable ObjectiveKindRegistry objectiveKinds) {
            this.objectiveKinds = objectiveKinds;
            return this;
        }

        /** The reward vocabulary. Defaults to empty, which pays nothing and says so. */
        @Nonnull
        public Builder rewardKinds(@Nullable RewardKindRegistry rewardKinds) {
            this.rewardKinds = rewardKinds;
            return this;
        }

        /** Where per-subject state lives. Defaults to an in-memory store that dies with the process. */
        @Nonnull
        public Builder store(@Nullable AchievementProgressStore store) {
            this.store = store;
            return this;
        }

        /** The consumer's say on progressing, earning, being paid, and being seen. Defaults to open. */
        @Nonnull
        public Builder gates(@Nonnull AchievementGates gates) {
            this.gates = gates;
            return this;
        }

        /**
         * The owner's "achievements are switched off for this player" switch, asked before the
         * maintenance pass earns anything on its own. The shared runtime hands in its COMPOSED gate
         * (every registered owner switch, ANDed) so a self-heal and a produced moment are refused
         * by the same answer. Defaults to {@link ProgressionSystemGate#OPEN}: an engine nobody
         * switched off.
         */
        @Nonnull
        public Builder systemGate(@Nonnull ProgressionSystemGate systemGate) {
            this.systemGate = systemGate;
            return this;
        }

        /** A side-channel seeing every tapped progress event. Defaults to nothing watching. */
        @Nonnull
        public Builder dispatchTap(@Nonnull ProgressDispatchTap tap) {
            this.tap = tap;
            return this;
        }

        /**
         * The factor vocabulary the engine reads a standing value through, which is what lets
         * {@link #selfHeal} settle a {@link ObjectiveKindRegistry#STAT_THRESHOLD} criterion for
         * itself rather than waiting for a producer to fire one. Unset leaves that kind purely
         * consumer-fired, exactly like every other kind in the vocabulary.
         *
         * <p>Pair it with {@link #factorContext}: the portable stat reading needs the entity it is
         * being asked about, so a registry with no way to reach one resolves nothing and writes
         * nothing. The same two knobs the quest engine takes, wired with the same two values.
         */
        @Nonnull
        public Builder factors(@Nullable FactorRegistry factors) {
            this.factors = factors;
            return this;
        }

        /**
         * How a subject becomes the context a factor provider reads (the entity, the store, the
         * world). Unset builds an empty context, which is enough for a provider that only needs the
         * authored argument and not enough for one that reads the subject.
         */
        @Nonnull
        public Builder factorContext(@Nullable Function<Subject, FactorContext> factorContext) {
            this.factorContext = factorContext;
            return this;
        }

        /**
         * Where a failed reward's replayable command goes for a later attempt. Leaving it unset means
         * a failed reward is reported and lost rather than queued.
         */
        @Nonnull
        public Builder rewardRetryQueue(@Nullable BiConsumer<Subject, String> rewardRetryQueue) {
            this.rewardRetryQueue = rewardRetryQueue;
            return this;
        }

        /** Where warnings go. Defaults to the library logger, guarded for a log-manager-less test JVM. */
        @Nonnull
        public Builder warn(@Nonnull Consumer<String> warn) {
            this.warn = warn;
            return this;
        }

        /**
         * Where a lifecycle MOMENT goes, for whatever reacts to one. Unlike the outbound native
         * events this is not switchable: a server that suppressed the cross-mod event bus still
         * wants its own toasts and jingles, which are what this carries.
         */
        @Nonnull
        public Builder feedbackHook(@Nonnull ProgressionFeedbackHook feedbackHook) {
            this.feedbackHook = feedbackHook;
            return this;
        }

        /** The clock, in epoch milliseconds. Inject one to exercise unlock instants. */
        @Nonnull
        public Builder clock(@Nonnull LongSupplier clock) {
            this.clock = clock;
            return this;
        }

        /** How many achievements a subject may pin. Defaults to 5; values below 1 are raised to 1. */
        @Nonnull
        public Builder maxPinned(int maxPinned) {
            this.maxPinned = Math.max(1, maxPinned);
            return this;
        }

        /**
         * Whether achievement moments are published on the shared engine event bus. On by default;
         * turn it off for an engine whose moments are nobody else's business.
         */
        @Nonnull
        public Builder nativeEvents(boolean nativeEvents) {
            this.nativeEvents = nativeEvents;
            return this;
        }

        /** Add a points milestone. They are checked lowest threshold first. */
        @Nonnull
        public Builder milestone(@Nonnull AchievementMilestone milestone) {
            milestones.add(milestone);
            milestones.sort(Comparator.comparingInt(AchievementMilestone::threshold));
            return this;
        }

        /** Add several points milestones. */
        @Nonnull
        public Builder milestones(@Nonnull List<AchievementMilestone> milestones) {
            this.milestones.addAll(milestones);
            this.milestones.sort(Comparator.comparingInt(AchievementMilestone::threshold));
            return this;
        }

        @Nonnull
        public AchievementEngine build() {
            return new AchievementEngine(this);
        }
    }

    /** Default warn sink: the library logger, guarded so a log-manager-less test JVM cannot crash. */
    private static final Consumer<String> DEFAULT_WARN =
            message -> SafeLog.warn("[achievement] " + message);
}
