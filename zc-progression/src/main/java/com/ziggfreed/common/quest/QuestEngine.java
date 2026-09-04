package com.ziggfreed.common.quest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorRegistry;
import com.ziggfreed.common.loot.reward.RewardGrants;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
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
import com.ziggfreed.common.quest.event.QuestEvents;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * A self-contained, per-consumer quest runtime. Built once through {@link #builder()}, it holds the
 * content catalogue, the vocabularies it is authored against, and the seams it reaches the outside
 * world through - then answers every quest question and performs every quest mutation.
 *
 * <p><b>Per consumer, not a shared global</b> - the same paradigm the dialogue engine keeps. The
 * vocabularies are fully populated before the engine is built and are never mutated afterwards, so
 * there is no registration race and one consumer's objective kinds never leak into another's.
 *
 * <p><b>The engine owns mechanics; the consumer owns everything else.</b> It knows about matching,
 * ordering, cooldowns, hand-ins, completion, and payout bookkeeping. It knows nothing about where
 * state lives ({@link QuestProgressStore}), who may take a quest ({@link QuestGates}), what a reward
 * is ({@link RewardKindRegistry}), what an inventory is ({@link QuestPossessionProbe} plus
 * {@link QuestInventoryConsumer}), or what any of it should be CALLED ({@link QuestI18n}). Swap
 * those five and the same engine runs a persistent world or a single round.
 *
 * <p><b>One optional seam sits beside them</b>: a factor vocabulary plus the context to read it in
 * ({@link Builder#factors} / {@link Builder#factorContext}). It is what lets the engine settle a
 * {@link ObjectiveKindRegistry#STAT_THRESHOLD} step by reading the value itself, since nothing will
 * ever fire to announce a state the player was already in. Leave it unwired and that kind is
 * consumer-fired like every other. See {@link #refreshStatThresholds(Subject, Quest)}.
 *
 * <p><b>Threading:</b> the engine holds no per-player state of its own, so it is as thread-safe as
 * the store behind it. Call it from whichever thread owns the player - typically the world thread,
 * which is also where the outbound events must be fired from.
 *
 * <p><b>Hand out {@link QuestStateReader}, not this.</b> A surface that only needs to LOOK at a
 * player's quests - a conversation deciding which line to show, most of all - takes the narrow read
 * seam, so no amount of drift can turn a rendering pass into an accept or a claim.
 */
public final class QuestEngine implements QuestStateReader {

    /**
     * The {@code reason} a {@code Quest_Parked} moment carries for a quest authored to be collected
     * rather than paid out the instant it finishes (anything in {@link Quest#claimRewards()}).
     */
    public static final String PARKED_COLLECT = "collect";

    /**
     * The {@code reason} a {@code Quest_Parked} moment carries when the consumer said the player
     * cannot receive the rewards right now (no room for them, in the usual case).
     */
    public static final String PARKED_NO_SPACE = "no_space";

    /**
     * The {@code reason} a {@code Quest_Parked} moment carries when the quest names a
     * {@link Quest#turnInAt() site} and the player finished it somewhere else.
     */
    public static final String PARKED_AWAY = "away";

    /** Whether a player may take a quest, and every reason they may not. */
    public record AcceptCheck(boolean allowed, @Nonnull List<String> reasons) {

        /** Yes, with nothing to explain. */
        public static final AcceptCheck ALLOWED = new AcceptCheck(true, List.of());

        /** The first refusal reason, or null when allowed. Enough for a one-line message. */
        @Nullable
        public String firstReason() {
            return reasons.isEmpty() ? null : reasons.get(0);
        }
    }

    /** How many of a quest's objectives are done, out of how many there are. */
    public record ObjectiveTally(int completed, int total) {

        /** Completion as a 0..1 fraction, safe when the quest has no objectives. */
        public double fraction() {
            return total <= 0 ? 0d : (double) completed / total;
        }
    }

    private final ObjectiveKindRegistry objectiveKinds;
    private final RewardKindRegistry rewardKinds;
    private final QuestProgressStore store;
    private final QuestPossessionProbe possession;
    private final QuestInventoryConsumer inventory;
    private final QuestGates gates;
    /** Is the quest system switched on for this player at all - the owner's switch, composed. */
    private final ProgressionSystemGate systemGate;
    private final ProgressDispatchTap tap;
    private final ProgressionFeedbackHook feedbackHook;
    /** Null when no factor vocabulary was wired, which switches every threshold re-check off. */
    @Nullable private final StatThresholdProbe statProbe;
    private final QuestI18n i18n;
    @Nullable private final BiConsumer<Subject, String> rewardRetryQueue;
    private final Consumer<String> warn;
    private final LongSupplier clock;
    private final int maxTracked;
    private final IntSupplier maxActive;

    /** No limit at all, which is what makes the log-full refusal unreachable rather than switched off. */
    private static final IntSupplier NO_CAP = () -> 0;
    private final boolean nativeEvents;

    /** The catalogue and its index, replaced together so a dispatch never sees a half-loaded pair. */
    private volatile Map<String, Quest> quests = Map.of();
    private volatile ObjectiveIndex index = ObjectiveIndex.EMPTY;

    /** Authoring mistakes that would otherwise repeat on every event, reported once per case. */
    private final Set<String> warnedOnce = ConcurrentHashMap.newKeySet();

    /** Backstop on {@link #armAutoAccepts}'s repeat passes; far above any real content chain. */
    private static final int MAX_ARM_PASSES = 16;

    /** True while {@link #armAutoAccepts} runs on this thread, so a nested settlement skips it. */
    private final ThreadLocal<Boolean> arming = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private QuestEngine(@Nonnull Builder b) {
        this.objectiveKinds = b.objectiveKinds != null ? b.objectiveKinds : new ObjectiveKindRegistry();
        this.rewardKinds = b.rewardKinds != null ? b.rewardKinds : new RewardKindRegistry();
        this.store = b.store != null ? b.store : new InMemoryQuestProgressStore();
        this.possession = b.possession;
        this.inventory = b.inventory;
        this.gates = b.gates;
        this.systemGate = b.systemGate;
        this.tap = b.tap;
        this.feedbackHook = b.feedbackHook;
        this.statProbe = StatThresholdProbe.of(b.factors, b.factorContext, b.warn);
        this.i18n = b.i18n;
        this.rewardRetryQueue = b.rewardRetryQueue;
        this.warn = b.warn;
        this.clock = b.clock;
        this.maxTracked = b.maxTracked;
        this.maxActive = b.maxActive;
        this.nativeEvents = b.nativeEvents;
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
    public QuestProgressStore store() {
        return store;
    }

    /** The naming seam, for a surface rendering quest text. */
    @Nonnull
    public QuestI18n i18n() {
        return i18n;
    }

    /** How many quests a player may pin at once. */
    public int maxTracked() {
        return maxTracked;
    }

    /**
     * How many quests a player may carry at once RIGHT NOW, or {@code 0} for no limit.
     *
     * <p>Read live rather than held, because the cap is usually an owner's config value and a reload
     * has to move it. Nothing caches the answer.
     */
    public int maxActive() {
        return Math.max(0, maxActive.getAsInt());
    }

    /** The engine's clock, in epoch milliseconds. Injected, so cooldown boundaries are testable. */
    public long now() {
        return clock.getAsLong();
    }

    // ==================== Content ====================

    /**
     * Replace the whole content catalogue and rebuild the dispatch index. Both are swapped together,
     * so a dispatch running concurrently sees either the old pair or the new one, never a mix.
     */
    public void setQuests(@Nonnull Collection<Quest> catalogue) {
        Map<String, Quest> byId = new LinkedHashMap<>();
        for (Quest quest : catalogue) {
            byId.put(quest.id(), quest);
        }
        Map<String, Quest> frozen = Map.copyOf(byId);
        ObjectiveIndex rebuilt = ObjectiveIndex.of(frozen.values(), Quest::id, Quest::objectives);
        this.quests = frozen;
        this.index = rebuilt;
        this.warnedOnce.clear();
        warnUnrecordableRepeats(frozen.values());
    }

    /**
     * Say so ONCE per quest when a quest asks for a calendar allowance or a lifetime cap and the
     * store behind this engine cannot remember completions, so those knobs quietly do nothing.
     *
     * <p>Here rather than at every accept because this is the one moment both facts are settled and
     * known together: the store was fixed when the engine was built, and the catalogue has just
     * arrived. That makes it a load-time line instead of a per-action cost.
     */
    private void warnUnrecordableRepeats(@Nonnull Collection<Quest> catalogue) {
        if (store.recordsCompletions()) {
            return;
        }
        for (Quest quest : catalogue) {
            Quest.Repeat repeat = quest.repeat();
            if (repeat == null || (repeat.reset() == null && repeat.maxCompletions() <= 0)) {
                continue;
            }
            warnOnce("repeat:" + quest.id(), "Quest '" + quest.id() + "' authors a Reset window or"
                    + " MaxCompletions, but this runtime's progress store cannot remember completions,"
                    + " so both are ignored and only its rolling cooldown applies");
        }
    }

    /** The quest with this id, or null. */
    @Nullable
    public Quest quest(@Nullable String questId) {
        return questId == null ? null : quests.get(questId);
    }

    /** The whole catalogue. */
    @Nonnull
    public Collection<Quest> quests() {
        return quests.values();
    }

    /** The kind-to-objectives index a dispatch walks; a producer can ask what is worth firing. */
    @Nonnull
    public ObjectiveIndex index() {
        return index;
    }

    // ==================== Status ====================

    /** What this quest EFFECTIVELY is for this player right now. See {@link QuestLifecycle}. */
    @Nonnull
    public QuestStatus status(@Nonnull Subject subject, @Nonnull Quest quest) {
        return QuestLifecycle.effectiveStatus(quest, subject, store, now());
    }

    /** Milliseconds left on this quest's cooldown for this player, or 0. */
    public long cooldownRemainingMs(@Nonnull Subject subject, @Nonnull Quest quest) {
        return QuestLifecycle.cooldownRemainingMs(quest, subject, store, now());
    }

    /** Is this player carrying this quest right now? */
    public boolean isActive(@Nonnull Subject subject, @Nonnull String questId) {
        return store.status(subject, questId) == QuestStatus.ACTIVE;
    }

    /** How many catalogued quests this player is carrying. Ids with no definition do not count. */
    public int activeCount(@Nonnull Subject subject) {
        int count = 0;
        for (String questId : store.knownQuestIds(subject)) {
            if (quests.containsKey(questId) && store.status(subject, questId) == QuestStatus.ACTIVE) {
                count++;
            }
        }
        return count;
    }

    /**
     * How many of this player's active quests are spending a quest-log SLOT, which is the number the
     * registered cap is measured against.
     *
     * <p>It is deliberately not {@link #activeCount}: an errand kept on a list of its own - a board
     * contract, say - is carried without ever appearing in a quest log, so counting it here would
     * quietly take slots away from a player who would have nothing on their log screen to explain
     * where they went, and would refuse them the next contract for a log they are not filling. What
     * makes the difference is the quest's own {@link Quest#occupiesLog()} switch, so whoever folds
     * the content says which it is.
     */
    public int logSlotsUsed(@Nonnull Subject subject) {
        int count = 0;
        for (String questId : store.knownQuestIds(subject)) {
            Quest quest = quests.get(questId);
            if (quest != null && quest.occupiesLog()
                    && store.status(subject, questId) == QuestStatus.ACTIVE) {
                count++;
            }
        }
        return count;
    }

    /** Every quest this player is carrying or has finished but not collected. */
    @Nonnull
    public List<Quest> activeAndUnclaimed(@Nonnull Subject subject) {
        List<Quest> out = new ArrayList<>();
        for (String questId : store.knownQuestIds(subject)) {
            Quest quest = quests.get(questId);
            if (quest == null) {
                continue;
            }
            QuestStatus status = store.status(subject, questId);
            if (status == QuestStatus.ACTIVE || status == QuestStatus.COMPLETED_UNCLAIMED) {
                out.add(quest);
            }
        }
        return out;
    }

    // ==================== Accept ====================

    /**
     * May this player take this quest, and if not, why not? Asks the owner's system switch first,
     * then checks the mechanical rules the engine owns (available, not already started, off
     * cooldown, room in the log) and then asks the consumer's {@link QuestGates}, keeping every
     * reason from both.
     *
     * <p><b>A switched-off system is refused with {@link QuestGates#REASON_SYSTEM_DISABLED}, and
     * that reason stands alone.</b> The switch is the same composed gate every produced moment is
     * checked against, so a server with quests off refuses an accept the same way it refuses the
     * progress that would have followed it, rather than taking the quest and then advancing it
     * nowhere; and no other reason is gathered beside it, because a prerequisite the player could go
     * and meet is no route into a system that is off.
     */
    @Nonnull
    public AcceptCheck canAccept(@Nonnull Subject subject, @Nonnull Quest quest) {
        if (!systemGate.enabled(ProgressionSystem.QUEST, subject)) {
            return new AcceptCheck(false, List.of(QuestGates.REASON_SYSTEM_DISABLED));
        }
        List<String> reasons = new ArrayList<>();
        if (!quest.available()) {
            reasons.add(QuestGates.REASON_UNAVAILABLE);
        }
        QuestStatus status = status(subject, quest);
        if (status == QuestStatus.ON_COOLDOWN
                || (status == QuestStatus.COMPLETED && quest.repeatable())) {
            // Ask the evaluator rather than inferring. ON_COOLDOWN is ONE display state covering a
            // running clock and a spent calendar window alike, and a repeatable reading COMPLETED is
            // one whose lifetime cap is spent - a caller deciding what to tell a player wants to know
            // which of the three it is.
            String reason = QuestLifecycle.repeatCheck(quest, subject, store, now()).reason();
            reasons.add(reason != null ? reason : QuestGates.REASON_ALREADY_STARTED);
        } else if (status != QuestStatus.NOT_STARTED) {
            reasons.add(QuestGates.REASON_ALREADY_STARTED);
        }
        int cap = maxActive.getAsInt();
        if (cap > 0 && quest.occupiesLog() && logSlotsUsed(subject) >= cap) {
            reasons.add(QuestGates.REASON_LOG_FULL);
        }
        // ONE gate question, not two: a gate answering both off the same requirement block reads it
        // once here, and accept is asked per quest every time a giver's list or a quest log renders.
        if (!gates.opensFor(subject, quest, reasons) && reasons.isEmpty()) {
            reasons.add(QuestGates.REASON_PREREQUISITES);
        }
        return reasons.isEmpty() ? AcceptCheck.ALLOWED : new AcceptCheck(false, List.copyOf(reasons));
    }

    /** {@link #accept(Subject, Quest, String)} from a surface with no place attached to it. */
    public boolean accept(@Nonnull Subject subject, @Nonnull Quest quest) {
        return accept(subject, quest, null);
    }

    /**
     * Take the quest on for this player: mark it active, seed every objective's progress, apply
     * whatever is already satisfied (see {@link #preSatisfiedFor}), record WHERE it was taken, and
     * pin it if the quest asks to be pinned.
     *
     * <p>Does NOT itself check eligibility beyond the cooldown - call {@link #canAccept} first when
     * the player is choosing. Callers that deliberately force a quest on somebody (a scripted start,
     * an administrator) skip the check on purpose, so it is not baked in here.
     *
     * <p><b>{@code siteId} is whatever the accepting surface calls the place it is</b> - a character
     * id at a conversation, the id of a fixture the player is standing at, null from a menu that is
     * nowhere in particular. It is only ever read back by a {@link QuestTurnInSite.Kind#ACCEPT_SITE}
     * quest, so a surface may pass it always and let the content decide whether it matters. Taking
     * the quest again records the new place, which is what makes a repeatable one work at a second
     * fixture.
     *
     * @return false only when a repeatable quest's own repeat rules refuse it
     */
    public boolean accept(@Nonnull Subject subject, @Nonnull Quest quest, @Nullable String siteId) {
        if (quest.repeatable()
                && !QuestLifecycle.repeatCheck(quest, subject, store, now()).available()) {
            return false;
        }
        store.setStatus(subject, quest.id(), QuestStatus.ACTIVE);

        Map<String, ObjectiveProgressState> progress = new LinkedHashMap<>();
        for (ObjectiveDef objective : quest.objectives()) {
            ObjectiveKind kind = objectiveKinds.kind(objective.kind());
            ObjectiveProgressState state = ObjectiveArithmetic.fresh(kind, objective);
            long preSatisfied = preSatisfiedFor(subject, quest, objective);
            if (preSatisfied > 0) {
                ObjectiveArithmetic.applyStanding(kind, objective, state, preSatisfied);
            }
            progress.put(objective.id(), state);
        }
        saveProgress(subject, quest.id(), progress, recordableSite(quest, siteId));

        if (quest.autoTrack()) {
            track(subject, quest.id());
        }
        store.markDirty(subject);
        fireAccepted(quest, subject);
        return true;
    }

    /**
     * The site as it will be STORED: null when there is nothing to store, and null with one warning
     * when the id cannot survive the progress format AND this quest is one that would have read it
     * back. Warning there rather than dropping it silently is the point - a quest that has to come
     * back to a place it could not record would refuse every collection later, with nothing anywhere
     * saying why.
     */
    @Nullable
    private String recordableSite(@Nonnull Quest quest, @Nullable String siteId) {
        if (siteId == null || siteId.isBlank() || QuestProgressPayload.isRecordableSite(siteId)) {
            return siteId;
        }
        QuestTurnInSite site = quest.turnInAt();
        if (site != null && site.isAcceptSite()) {
            warnOnce("site:" + siteId, "Quest '" + quest.id() + "' was taken at '" + siteId
                    + "', which uses a character the progress format reserves ("
                    + QuestProgressStore.DEFAULT_RESERVED_CHARACTERS + "), so the place cannot be"
                    + " remembered and the quest cannot be brought back to it");
        }
        return null;
    }

    /**
     * Accept every available auto-accept quest this player is eligible for and not already past,
     * then immediately settle any whose objectives are already met. Call once when a player becomes
     * ready, after {@link #selfHeal}, so a repeatable that has come back around reads offerable.
     * The engine also re-runs it itself whenever a quest completes, so an auto-accept quest gated
     * on the one that just finished arms in the same moment rather than on the next login.
     *
     * <p>Skipped outright for a player the owner's system switch has quests OFF for: an auto-accept
     * is an accept the player never asked for, so on a server with quests switched off it would
     * quietly hand out a quest that then advances nowhere. {@link #canAccept} refuses each one for
     * the same reason; asking the switch once here is what keeps a switched-off login from walking
     * the whole catalogue to be told no per quest.
     *
     * @return how many were accepted
     */
    public int autoAcceptAvailable(@Nonnull Subject subject) {
        if (!systemGate.enabled(ProgressionSystem.QUEST, subject)) {
            return 0;
        }
        int accepted = 0;
        for (Quest quest : quests.values()) {
            if (!quest.autoAccept() || !quest.available()) {
                continue;
            }
            if (status(subject, quest) != QuestStatus.NOT_STARTED) {
                continue;
            }
            // The giver is recorded as the accept site: an auto-accept is the giver handing the
            // quest over without being walked to, so an ACCEPT_SITE hand-in resolves at the giver
            // instead of nowhere, and the giver's own list counts the quest as taken there.
            if (!canAccept(subject, quest).allowed() || !accept(subject, quest, quest.npcViewId())) {
                continue;
            }
            checkCompletion(subject, quest);
            accepted++;
        }
        return accepted;
    }

    /**
     * Arm whatever a completion just unlocked: run the auto-accept pass again, repeated until a
     * pass arms nothing, so a chain of auto-accept quests each gated on the one before it arms as
     * far as it can the moment a link finishes - not on the player's next login.
     *
     * <p>Re-entrancy-guarded per thread rather than per subject: an armed quest whose objectives
     * are already met settles inside the pass, and that settlement lands back here. The outer
     * loop's next iteration is the one that picks up what that settlement unlocked, so the nested
     * call has nothing to add and is skipped instead of stacking.
     */
    private void armAutoAccepts(@Nonnull Subject subject) {
        if (Boolean.TRUE.equals(arming.get())) {
            return;
        }
        arming.set(Boolean.TRUE);
        try {
            int passes = 0;
            while (autoAcceptAvailable(subject) > 0 && ++passes < MAX_ARM_PASSES) {
                // Each pass consumes at least one NOT_STARTED quest, so this terminates on its
                // own; the cap is a backstop against a store that cannot hold what accept wrote.
            }
        } catch (Throwable t) {
            warn.accept("auto-accept arming after a completion failed: " + t.getMessage());
        } finally {
            arming.set(Boolean.FALSE);
        }
    }

    // ==================== Dispatch ====================

    /** {@link #dispatch} with {@link DispatchOptions#FULL} and no zone. */
    public void dispatch(@Nonnull Subject subject, @Nonnull String kindId, @Nonnull String target,
                         @Nullable String qualifier, long amount) {
        dispatch(subject, kindId, target, qualifier, amount, null, DispatchOptions.FULL);
    }

    /**
     * Feed one progress event to this player's quests: advance every ACTIVE, unlocked, matching
     * objective, settle any quest that just finished, and (in a tapped dispatch) let the consumer's
     * {@link ProgressDispatchTap} see the event whether or not anything matched.
     *
     * <p>Order of business, and each step is there for a reason:
     * <ol>
     *   <li>the tap fires FIRST, before any objective is touched, so a counter records the action
     *   even when no quest wanted it;
     *   <li>the index narrows the work to objectives authored against this kind;
     *   <li>a quest that is not active may still auto-accept itself here, which is what lets a
     *   quest start on the player's first qualifying action rather than on a menu visit;
     *   <li>target, qualifier, and zone must all match, then the objective must be unlocked by the
     *   quest's ordering;
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
            warnOnce("kind:" + kindId, "Objective kind '" + kindId + "' is authored on a quest but is"
                    + " not part of this engine's vocabulary - those objectives can never progress");
            return;
        }

        boolean changed = false;
        for (ObjectiveIndex.Entry entry : entries) {
            ObjectiveDef objective = entry.objective();
            // A follow-up fire under an additional id must not re-count a match-all objective.
            if (options.targetedOnly() && objective.target().isBlank()) {
                continue;
            }
            Quest quest = quests.get(entry.ownerId());
            if (quest == null) {
                continue;
            }
            if (!isActive(subject, quest.id()) && !tryAutoAcceptOnEvent(subject, quest)) {
                continue;
            }
            if (!objective.matches(target, qualifier) || !objective.matchesZone(zone)) {
                continue;
            }
            if (!objectiveActive(subject, quest, objective.id())) {
                continue;
            }

            Map<String, ObjectiveProgressState> progress = progressOf(subject, quest.id());
            ObjectiveProgressState state = progress.computeIfAbsent(objective.id(),
                    key -> ObjectiveArithmetic.fresh(kind, objective));
            if (state.isCompleted()) {
                continue;
            }
            int before = state.current();
            boolean justCompleted = ObjectiveArithmetic.apply(kind, objective, state, amount);
            if (state.current() == before && !justCompleted) {
                continue;
            }
            saveProgress(subject, quest.id(), progress);
            changed = true;
            fireObjectiveProgressed(quest, objective, subject, state, justCompleted);
            // A quest that just moved is a quest somebody is playing right now, so it is also the
            // cheapest possible moment to re-read its threshold steps: no timer, no sweep over
            // content nobody is carrying, and no threshold left showing as outstanding for the rest
            // of a session because the value it watches changed while nothing fired.
            refreshStatThresholds(subject, quest);
            if (justCompleted) {
                checkCompletion(subject, quest);
            }
        }
        if (changed) {
            store.markDirty(subject);
        }
    }

    /**
     * A quest the player is not carrying may still start right here, if it is an auto-accept quest
     * they are eligible for and have not already been through. Anything else is skipped.
     */
    private boolean tryAutoAcceptOnEvent(@Nonnull Subject subject, @Nonnull Quest quest) {
        if (!quest.autoAccept() || !quest.available()) {
            return false;
        }
        if (status(subject, quest) != QuestStatus.NOT_STARTED) {
            return false;
        }
        return canAccept(subject, quest).allowed() && accept(subject, quest);
    }

    // ==================== Ordering ====================

    /**
     * Is this objective unlocked for this player right now?
     *
     * <p>Ordering has two dialects and the quest picks by what it authored. If ANY objective carries
     * an {@link ObjectiveDef#order()}, orders decide: an objective is unlocked once every
     * objective with a strictly LOWER non-zero order is complete, and an objective with order 0 is
     * always unlocked. If no objective carries an order, the quest's {@link Quest#sequential()} flag
     * decides: either strictly one at a time in authored order, or all at once.
     *
     * <p>Note this stays true for an objective that is already COMPLETE - it answers "is this
     * unlocked", not "is this outstanding".
     */
    public boolean objectiveActive(@Nonnull Subject subject, @Nonnull Quest quest,
                                   @Nonnull String objectiveId) {
        if (quest.hasOrderedObjectives()) {
            return activeByOrder(subject, quest, objectiveId);
        }
        if (quest.sequential()) {
            return isNextObjective(subject, quest, objectiveId);
        }
        return true;
    }

    private boolean activeByOrder(@Nonnull Subject subject, @Nonnull Quest quest,
                                  @Nonnull String objectiveId) {
        ObjectiveDef target = quest.objective(objectiveId);
        int targetOrder = target != null ? target.order() : 0;
        if (targetOrder == 0) {
            return true;
        }
        Map<String, ObjectiveProgressState> progress = progressOf(subject, quest.id());
        for (ObjectiveDef objective : quest.objectives()) {
            int order = objective.order();
            if (order > 0 && order < targetOrder) {
                ObjectiveProgressState state = progress.get(objective.id());
                if (state == null || !state.isCompleted()) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean isNextObjective(@Nonnull Subject subject, @Nonnull Quest quest,
                                    @Nonnull String objectiveId) {
        Map<String, ObjectiveProgressState> progress = progressOf(subject, quest.id());
        for (ObjectiveDef objective : quest.objectives()) {
            if (objective.id().equals(objectiveId)) {
                return true;
            }
            ObjectiveProgressState state = progress.get(objective.id());
            if (state == null || !state.isCompleted()) {
                return false;
            }
        }
        return false;
    }

    /**
     * The objectives of the quest's CURRENT step, for a compact display that cannot show them all:
     * every unordered objective plus the objectives of the lowest order group that still has
     * something outstanding. Once a step is done the next becomes current, so the list advances by
     * itself. A quest authoring no orders returns all of its objectives.
     */
    @Nonnull
    public List<ObjectiveDef> activeStepObjectives(@Nonnull Subject subject, @Nonnull Quest quest) {
        if (!quest.hasOrderedObjectives()) {
            return quest.objectives();
        }
        Map<String, ObjectiveProgressState> progress = progressOf(subject, quest.id());

        int activeOrder = Integer.MAX_VALUE;
        int maxOrder = 0;
        for (ObjectiveDef objective : quest.objectives()) {
            int order = objective.order();
            if (order <= 0) {
                continue;
            }
            maxOrder = Math.max(maxOrder, order);
            ObjectiveProgressState state = progress.get(objective.id());
            boolean done = state != null && state.isCompleted();
            if (!done && order < activeOrder) {
                activeOrder = order;
            }
        }
        // Every ordered objective is done: the final step stays on screen rather than nothing at all.
        if (activeOrder == Integer.MAX_VALUE) {
            activeOrder = maxOrder;
        }

        List<ObjectiveDef> out = new ArrayList<>();
        for (ObjectiveDef objective : quest.objectives()) {
            if (objective.order() <= 0 || objective.order() == activeOrder) {
                out.add(objective);
            }
        }
        return out;
    }

    // ==================== Hand-in ====================

    /**
     * The first unlocked, outstanding hand-in objective on this quest that can be handed in at
     * {@code atId}, or null when there is none.
     *
     * <p>An objective with no {@link ObjectiveDef#turnInLockId()} can be handed in anywhere, so
     * it always qualifies; one locked to a place qualifies only when {@code atId} matches it
     * (case-insensitively). Pass null for {@code atId} to mean "somewhere unlocked", which skips
     * every place-locked hand-in.
     */
    @Nullable
    public ObjectiveDef firstActiveTurnIn(@Nonnull Subject subject, @Nonnull Quest quest,
                                               @Nullable String atId) {
        Map<String, ObjectiveProgressState> progress = progressOf(subject, quest.id());
        for (ObjectiveDef objective : quest.objectives()) {
            if (!isTurnIn(objective)) {
                continue;
            }
            String lockedTo = objective.turnInLockId();
            if (lockedTo != null && (atId == null || !lockedTo.equalsIgnoreCase(atId))) {
                continue;
            }
            ObjectiveProgressState state = progress.get(objective.id());
            if (state != null && state.isCompleted()) {
                continue;
            }
            if (!objectiveActive(subject, quest, objective.id())) {
                continue;
            }
            return objective;
        }
        return null;
    }

    /**
     * Is this quest at the point where {@code atId} is where the player should go? True when the
     * quest is genuinely ACTIVE (so a repeatable that has come back around is NOT ready) and its
     * outstanding step resolves here: either a hand-in that can be handed in here, or a first
     * outstanding objective whose target IS this place.
     *
     * <p>Deliberately ignores the inventory, so a listing can rank a quest as "go here next" even
     * when the player is not carrying everything yet - {@link #canDeliverTurnInAt} is the stricter
     * check for actually OFFERING the hand-in.
     */
    public boolean readyToTurnInAt(@Nonnull Subject subject, @Nonnull Quest quest, @Nullable String atId) {
        if (atId == null || atId.isBlank()) {
            return false;
        }
        if (status(subject, quest) != QuestStatus.ACTIVE) {
            return false;
        }
        if (firstActiveTurnIn(subject, quest, atId) != null) {
            return true;
        }
        Map<String, ObjectiveProgressState> progress = progressOf(subject, quest.id());
        for (ObjectiveDef objective : quest.objectives()) {
            ObjectiveProgressState state = progress.get(objective.id());
            if (state != null && state.isCompleted()) {
                continue;
            }
            if (!objectiveActive(subject, quest, objective.id())) {
                continue;
            }
            // The FIRST outstanding step decides: a quest mid-way through something else is not ready.
            return resolvesAt(objective, atId);
        }
        return false;
    }

    /**
     * Does this step point AT {@code atId}? Only a step whose kind declares a place-typed target can
     * (a block id or an item id names a thing, not somewhere to stand), and only when it names one:
     * a blank target matches every identifier there is, so reading it as a destination would point
     * every such quest at every place at once.
     *
     * <p>The comparison is one whole id against one whole id, ignoring case, exactly as every other
     * place is compared here. The authored {@link ObjectiveDef#matchMode()} is deliberately not
     * consulted: it is the dialect a fired EVENT is matched with, where a target is written to catch
     * a family of ids, and a family written to catch things would catch places too.
     */
    private boolean resolvesAt(@Nonnull ObjectiveDef objective, @Nonnull String atId) {
        if (!objectiveKinds.isPlaceTargeted(objective.kind())) {
            return false;
        }
        String target = objective.target();
        return !target.isBlank() && target.equalsIgnoreCase(atId);
    }

    /**
     * {@link #readyToTurnInAt} AND the player is carrying SOMETHING the step can take. Use this to
     * OFFER a hand-in, so an offer cannot be shown and then silently do nothing; use the looser
     * {@link #readyToTurnInAt} for listing and ranking, which asks only where the player should go.
     *
     * <p>A report-back hand-in - one whose target names no item - is deliverable with an empty
     * inventory. An item hand-in needs ONE, not the whole remaining amount: a hand-in credits
     * whatever the player actually brought ({@link #attemptTurnIn}), so a player chipping away at a
     * large delivery over several visits has to be offered the button on each of them. Whether the
     * delivery would FINISH the quest is a different question, and {@link #settlesTurnInAt} is the
     * one that answers it.
     *
     * <p>A quest with NO outstanding hand-in at all is not deliverable, however plainly this place is
     * where its next step happens. A step that says "go and speak to them" resolves here without
     * anything changing hands, and {@link #attemptTurnIn} acts only on hand-in objectives, so
     * answering yes would offer a delivery that provably does nothing. {@link #readyToTurnInAt} is
     * the question for "is this where the player should go".
     */
    public boolean canDeliverTurnInAt(@Nonnull Subject subject, @Nonnull Quest quest,
                                      @Nullable String atId) {
        if (!readyToTurnInAt(subject, quest, atId)) {
            return false;
        }
        ObjectiveDef objective = firstActiveTurnIn(subject, quest, atId);
        if (objective == null) {
            return false;
        }
        return carriesSomethingFor(subject, quest, objective);
    }

    /**
     * Would handing over everything the player is carrying, right here, FINISH this quest?
     *
     * <p>The question a surface asks before it tells the player a quest is ready to hand in: every
     * step that is not already done has to be a hand-in this place accepts, and the player has to be
     * carrying the WHOLE of what each one still owes. So a quest with other work outstanding is not
     * ready however much of the delivery is in the bag, and a delivery that is short is not ready
     * either. Both of those are answered instead by {@link #canDeliverTurnInAt}, which offers the
     * partial hand-in that gets the player closer.
     *
     * <p>Ordering is deliberately not consulted. A hand-in step locked behind another hand-in step
     * unlocks the moment that one is credited, and {@link #attemptAllTurnIns} delivers a whole run
     * of them in one pass, so a character owed several deliveries settles on a single press - which
     * is what a surface saying "ready" has already promised.
     */
    public boolean settlesTurnInAt(@Nonnull Subject subject, @Nonnull Quest quest,
                                   @Nullable String atId) {
        if (atId == null || atId.isBlank() || status(subject, quest) != QuestStatus.ACTIVE) {
            return false;
        }
        Map<String, ObjectiveProgressState> progress = progressOf(subject, quest.id());
        boolean anyHandIn = false;
        for (ObjectiveDef objective : quest.objectives()) {
            ObjectiveProgressState state = progress.get(objective.id());
            if (state != null && state.isCompleted()) {
                continue;
            }
            if (!isTurnIn(objective) || !acceptsHandInAt(objective, atId)) {
                return false;
            }
            String itemId = objective.target();
            if (!itemId.isEmpty()
                    && !possession.holds(subject, itemId, remainingFor(subject, quest, objective))) {
                return false;
            }
            anyHandIn = true;
        }
        return anyHandIn;
    }

    /** Does the player hold at least one of what this step still owes, or does it owe nothing? */
    private boolean carriesSomethingFor(@Nonnull Subject subject, @Nonnull Quest quest,
                                        @Nonnull ObjectiveDef objective) {
        String itemId = objective.target();
        if (itemId.isEmpty()) {
            return true;
        }
        return remainingFor(subject, quest, objective) <= 0 || possession.holds(subject, itemId, 1);
    }

    /**
     * May this hand-in step be handed in at {@code atId}? A step locked to nowhere is accepted
     * anywhere, so it passes on any id; a place-locked one passes only at the id it names.
     */
    private boolean acceptsHandInAt(@Nonnull ObjectiveDef objective, @Nullable String atId) {
        String lockedTo = objective.turnInLockId();
        return lockedTo == null || (atId != null && lockedTo.equalsIgnoreCase(atId));
    }

    /** How much this objective still owes: required minus current, never negative. */
    public int remainingFor(@Nonnull Subject subject, @Nonnull Quest quest,
                            @Nonnull ObjectiveDef objective) {
        ObjectiveProgressState state = progressOf(subject, quest.id()).get(objective.id());
        if (state == null) {
            return objective.amountAsInt();
        }
        return Math.max(0, state.required() - state.current());
    }

    /**
     * Perform a hand-in: take what the objective asks for and credit exactly that much.
     *
     * <p>Two shapes, told apart by whether the objective names a target:
     * <ul>
     *   <li>a BLANK target is a report-back - there is nothing to hand over, so the step simply
     *   completes on the interaction;
     *   <li>a named target is an item delivery - the {@link QuestInventoryConsumer} takes up to what
     *   is still owed and reports how many it really got, and PARTIAL delivery is credited, so a
     *   player can chip away at a large hand-in over several visits.
     * </ul>
     *
     * @return how many were credited; {@code 0} when the quest is not active, the objective is not a
     * hand-in, it is locked or already done, or the player had none
     */
    public int attemptTurnIn(@Nonnull Subject subject, @Nonnull Quest quest,
                             @Nonnull String objectiveId) {
        return attemptTurnIn(subject, quest, objectiveId, null);
    }

    /**
     * {@link #attemptTurnIn(Subject, Quest, String)} performed AT {@code atId} - the character or
     * fixture the player is standing at.
     *
     * <p>Passing it matters for the last step of a quest that names a
     * {@link Quest#turnInAt() collection site}: the hand-in that finishes such a quest at its own
     * site pays out there and then, while the same hand-in with nowhere named parks it for the player
     * to come back and collect. A caller that picked the objective through
     * {@link #firstActiveTurnIn} already holds the id it should pass here.
     */
    public int attemptTurnIn(@Nonnull Subject subject, @Nonnull Quest quest,
                             @Nonnull String objectiveId, @Nullable String atId) {
        if (!isActive(subject, quest.id())) {
            return 0;
        }
        ObjectiveDef objective = quest.objective(objectiveId);
        if (objective == null || !isTurnIn(objective)) {
            return 0;
        }
        if (!objectiveActive(subject, quest, objectiveId)) {
            return 0;
        }
        Map<String, ObjectiveProgressState> progress = progressOf(subject, quest.id());
        ObjectiveProgressState state = progress.computeIfAbsent(objectiveId,
                key -> ObjectiveArithmetic.fresh(objectiveKinds.kind(objective.kind()), objective));
        if (state.isCompleted()) {
            return 0;
        }
        int remaining = Math.max(0, state.required() - state.current());
        if (remaining <= 0) {
            return 0;
        }

        int credited;
        String itemId = objective.target();
        if (itemId.isEmpty()) {
            credited = remaining;
        } else {
            credited = clampTaken(subject, itemId, remaining);
            if (credited <= 0) {
                return 0;
            }
        }
        boolean justCompleted = state.advance(credited);
        saveProgress(subject, quest.id(), progress);
        store.markDirty(subject);
        fireObjectiveProgressed(quest, objective, subject, state, justCompleted);
        if (justCompleted) {
            checkCompletion(subject, quest, atId);
        }
        return credited;
    }

    /**
     * Hand in EVERY outstanding step this place accepts, in one pass, and report how many items were
     * credited in total.
     *
     * <p>The call a hand-in button makes, because a character owed three separate deliveries is one
     * errand to the player and pressing the button three times to discharge it reads as the first
     * two presses having failed. Each step is delivered through {@link #attemptTurnIn}, so every one
     * fires its own progress moment and a PARTIAL delivery is credited exactly as it is on its own -
     * a player carrying half of what is owed leaves half of it here and keeps the rest of the quest.
     *
     * <p>The pass repeats while something is still being credited rather than walking the steps once,
     * which is what lets a hand-in step LOCKED behind another hand-in step settle in the same press:
     * crediting the first unlocks the second, and the next lap picks it up. It stops the moment a lap
     * credits nothing, so a step the player cannot pay costs one wasted lap and no more.
     *
     * @return how many items were credited across every step, {@code 0} when nothing could be
     * handed over at all
     */
    public int attemptAllTurnIns(@Nonnull Subject subject, @Nonnull Quest quest,
                                 @Nullable String atId) {
        int total = 0;
        int laps = 0;
        while (++laps < MAX_ARM_PASSES) {
            ObjectiveDef step = firstActiveTurnIn(subject, quest, atId);
            if (step == null) {
                break;
            }
            int credited = attemptTurnIn(subject, quest, step.id(), atId);
            if (credited <= 0) {
                break;
            }
            total += credited;
            // A settled quest has nothing left to be handed: carrying on would ask an inactive
            // quest for its next step and be refused once per lap.
            if (status(subject, quest) != QuestStatus.ACTIVE) {
                break;
            }
        }
        return total;
    }

    /**
     * Ask the inventory seam to take up to {@code remaining} and clamp what it claims into the legal
     * range. A seam over-reporting would credit more than it took, which is why the number is not
     * trusted as given.
     */
    private int clampTaken(@Nonnull Subject subject, @Nonnull String itemId, int remaining) {
        int taken;
        try {
            taken = inventory.take(subject, itemId, remaining);
        } catch (Throwable t) {
            warn.accept("hand-in could not take '" + itemId + "': " + t.getMessage());
            return 0;
        }
        return Math.max(0, Math.min(remaining, taken));
    }

    /** Is this objective a hand-in? Decided by the reserved kind id, so it needs no extra flag. */
    private static boolean isTurnIn(@Nonnull ObjectiveDef objective) {
        return "TURN_IN".equalsIgnoreCase(objective.kind().trim());
    }

    // ==================== Where a quest may be completed ====================

    /**
     * May this player complete and collect THIS quest here? The ONE site rule, asked by every surface
     * that offers a completion and enforced by the engine inside the completion path itself, so a
     * surface that forgets to ask cannot let a wrong one through.
     *
     * <p>It answers the SITE question and nothing else - not whether the objectives are done, not
     * whether the quest is even active. A caller deciding whether to show a button asks this
     * ALONGSIDE the state it was already reading, and the engine ANDs it into
     * {@link #claim(Subject, Quest, String)} and
     * {@link #checkCompletion(Subject, Quest, String)}.
     *
     * <p>The rules, in full:
     * <ul>
     *   <li>a quest with no {@link Quest#turnInAt() site} may be completed ANYWHERE, {@code atId} or
     *   no {@code atId} - that is the default and the great majority of content;</li>
     *   <li>a site-bound quest refuses a null or blank {@code atId} outright: a claim from a log or a
     *   book is a claim from nowhere;</li>
     *   <li>a {@link QuestTurnInSite.Kind#CHARACTER} site passes when the id matches, ignoring case.
     *   ONE id is compared, so a character answering to several is handled by the caller asking once
     *   per id it answers to - which is how the rest of the at-a-character reads here work, and why
     *   this module needs no identity registry of its own;</li>
     *   <li>a {@link QuestTurnInSite.Kind#ACCEPT_SITE} site passes when {@code atId} matches the place
     *   this player took the quest from. Progress recorded before the quest carried that form has no
     *   place stored, so it matches nowhere until the quest is taken again - which is why the form
     *   belongs to content that comes back round rather than to a one-shot.</li>
     * </ul>
     */
    public boolean canCompleteAt(@Nonnull Subject subject, @Nonnull Quest quest,
                                 @Nullable String atId) {
        QuestTurnInSite site = quest.turnInAt();
        if (site == null) {
            return true;
        }
        return site.matches(atId, site.isAcceptSite() ? acceptSiteOf(subject, quest.id()) : null);
    }

    /**
     * Where this player took this quest from, or null when nothing was recorded - an accept from a
     * surface with no place, or progress that predates the quest asking. Read back out of the same
     * payload the objective progress lives in.
     */
    @Nullable
    public String acceptSiteOf(@Nonnull Subject subject, @Nonnull String questId) {
        return QuestProgressPayload.acceptSite(store.progressPayload(subject, questId));
    }

    // ==================== Completion, claim, close-out ====================

    /** {@link #checkCompletion(Subject, Quest, String)} from nowhere in particular. */
    public void checkCompletion(@Nonnull Subject subject, @Nonnull Quest quest) {
        checkCompletion(subject, quest, null);
    }

    /**
     * Settle a quest whose objectives may now all be met: nothing happens unless every objective is
     * complete, and then the quest either pays out or parks for the player to collect.
     *
     * <p>It parks when the quest has rewards that wait to be collected
     * ({@link Quest#requiresClaim()}), when
     * the consumer says the player cannot receive the rewards right now (a full inventory - what
     * stops a payout from vanishing into nowhere), or when the quest names a
     * {@link Quest#turnInAt() site} that {@code atId} is not. A parked quest is not a lost one: the
     * player collects it by coming to the place, through {@link #claim(Subject, Quest, String)}.
     *
     * <p>{@code atId} is where the player is as the last step lands - the character they are talking
     * to, the fixture they are standing at - so a quest finished AT its own site still pays out on
     * the spot. Pass null from anywhere the moment has no place.
     */
    public void checkCompletion(@Nonnull Subject subject, @Nonnull Quest quest, @Nullable String atId) {
        if (store.status(subject, quest.id()) != QuestStatus.ACTIVE) {
            return;
        }
        if (!allObjectivesComplete(subject, quest)) {
            return;
        }
        String parkedReason = parkedReason(subject, quest, atId);
        if (parkedReason != null) {
            markUnclaimed(subject, quest);
            store.markDirty(subject);
            fireCompleted(quest, subject, parkedReason);
            // Parking records the completion, so whatever this quest was gating is unlocked NOW,
            // not when the reward is eventually collected.
            armAutoAccepts(subject);
            return;
        }
        markCompleted(subject, quest);
        fireCompleted(quest, subject, null);
        RewardGrants.GrantOutcome outcome = grantRewards(subject, quest);
        store.markDirty(subject);
        // A quest that pays out the instant it finishes is a transaction boundary exactly like a
        // collected one - but only when something was actually paid. A quest carrying no rewards
        // has nothing a crash could cost the player, so it waits for the batch like any other
        // change, and a sweep that settles several such quests costs a backend nothing.
        if (outcome.anyDelivered()) {
            store.flush(subject);
        }
        fireClaimed(quest, subject, outcome, false);
        armAutoAccepts(subject);
    }

    /**
     * Why a finished quest parks instead of paying out, as the token the {@code Quest_Parked}
     * moment carries under {@code reason}, or null when it pays out now.
     *
     * <p>Three causes, reported in the order they are decided: {@link #PARKED_COLLECT} when the
     * quest carries rewards authored to be collected rather than paid on the spot
     * ({@link Quest#requiresClaim()}), {@link #PARKED_NO_SPACE} when the consumer says the player cannot receive the rewards
     * right now, {@link #PARKED_AWAY} when the quest names a {@link Quest#turnInAt() site} and
     * {@code atId} is not it. A quest that is both authored to be collected and out of room reads
     * as collected: that is the case that was always going to park, and the room will matter when
     * they come to collect.
     */
    @Nullable
    private String parkedReason(@Nonnull Subject subject, @Nonnull Quest quest,
                                @Nullable String atId) {
        if (quest.requiresClaim()) {
            return PARKED_COLLECT;
        }
        if (!gates.canReceiveRewards(subject, quest)) {
            return PARKED_NO_SPACE;
        }
        if (!canCompleteAt(subject, quest, atId)) {
            return PARKED_AWAY;
        }
        return null;
    }

    /** Are all of this quest's objectives complete for this player? */
    public boolean allObjectivesComplete(@Nonnull Subject subject, @Nonnull Quest quest) {
        Map<String, ObjectiveProgressState> progress = progressOf(subject, quest.id());
        for (ObjectiveDef objective : quest.objectives()) {
            ObjectiveProgressState state = progress.get(objective.id());
            if (state == null || !state.isCompleted()) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@link #claim(Subject, Quest, String)} from a surface with no place attached - a log, a book,
     * a menu. A quest that names a {@link Quest#turnInAt() site} refuses this call by definition:
     * "anywhere" is precisely what such a quest is not.
     */
    public boolean claim(@Nonnull Subject subject, @Nonnull Quest quest) {
        return claim(subject, quest, null);
    }

    /**
     * Collect a parked quest's rewards at {@code atId}. Refuses unless the quest really is waiting to
     * be collected, the player is somewhere the quest allows ({@link #canCompleteAt}), and the
     * consumer says the player can receive them.
     *
     * <p>The site check lives HERE, in the one method that pays a parked quest out, so a surface
     * cannot bypass it by forgetting to ask. Every surface should still ASK first, because a refusal
     * a player was never warned about reads as a broken button.
     *
     * @return true when the rewards were granted
     */
    public boolean claim(@Nonnull Subject subject, @Nonnull Quest quest, @Nullable String atId) {
        if (store.status(subject, quest.id()) != QuestStatus.COMPLETED_UNCLAIMED) {
            return false;
        }
        if (!canCompleteAt(subject, quest, atId)) {
            return false;
        }
        if (!gates.canReceiveRewards(subject, quest)) {
            return false;
        }
        markCompleted(subject, quest);
        RewardGrants.GrantOutcome outcome = grantRewards(subject, quest);
        // Collecting is a player-owned transaction boundary: they pressed the button and it must
        // stick, whether or not this particular quest had anything to hand over. Unlike the
        // payout paths, which the engine reaches on its own and which commit only when they paid.
        store.markDirty(subject);
        store.flush(subject);
        fireClaimed(quest, subject, outcome, true);
        // Collecting is when a quest reads as FINISHED to a prerequisite, so it is also when
        // whatever was waiting on it can arm. Without this a chain behind a quest that parks its
        // reward waits for the player's next login, because the pass that ran when the quest parked
        // saw a prerequisite that was not finished yet.
        armAutoAccepts(subject);
        return true;
    }

    /**
     * Close a quest out and pay it, regardless of whether its objectives were met - for a scripted
     * skip or an administrator.
     *
     * <p>Idempotent for a one-shot quest already finished: it is left alone and NOT paid twice, so a
     * double click cannot double-grant.
     *
     * <p>It ignores the quest's {@link Quest#turnInAt() site} exactly as it ignores the objectives:
     * both are the content's rules, and this is the call that exists to set them aside. Nothing a
     * player can reach comes through here.
     *
     * @return true when this call closed the quest out
     */
    public boolean forceComplete(@Nonnull Subject subject, @Nonnull Quest quest) {
        if (store.status(subject, quest.id()) == QuestStatus.COMPLETED && !quest.repeatable()) {
            return false;
        }
        markCompleted(subject, quest);
        fireCompleted(quest, subject, null);
        RewardGrants.GrantOutcome outcome = grantRewards(subject, quest);
        store.markDirty(subject);
        // Commits when it paid, on the same rule as the settle-on-the-spot path above.
        if (outcome.anyDelivered()) {
            store.flush(subject);
        }
        fireClaimed(quest, subject, outcome, false);
        armAutoAccepts(subject);
        return true;
    }

    /**
     * The ONE "this quest is finished" rule: set the terminal status, record the completion unless
     * it was already recorded when the quest parked, count the CLAIM, and start a
     * {@code CLAIM}-anchored cooldown.
     *
     * <p>Reading the PRIOR status is what tells the two apart with no bookkeeping flag anywhere. A
     * prior {@link QuestStatus#COMPLETED_UNCLAIMED} means this call is the collect of an
     * already-parked quest, so the completion is on the record already and the clock, if it is
     * anchored to {@code COMPLETE}, was started back then. Anything else - the settle-on-the-spot path, an
     * administrator, a scripted skip - is the moment the quest finished.
     *
     * <p><b>Every route to {@link QuestStatus#COMPLETED} pays the quest out</b> (the
     * settle-on-the-spot path, the collect of a parked one, and the administrator's close-out all grant immediately
     * after calling this), so this method is the one instant a CLAIM happens and the only place
     * {@link QuestProgressStore.CompletionRecord#claimedCount()} is raised. A caller reaching this
     * from OUTSIDE the engine takes on the same obligation: it records a payout, so a surface that
     * owes the player a reward must hand it over, and one that does not owe them anything has no
     * business calling it.
     *
     * <p><b>A one-shot keeps no completion record at all.</b> The record exists for the rules a
     * {@link Quest.Repeat} group carries, so a quest with no repeat group returns here before either
     * tally moves, and the completion COUNT reads zero for it however many times it was finished.
     * The terminal STATUS is what a one-shot is asked about instead.
     */
    public void markCompleted(@Nonnull Subject subject, @Nonnull Quest quest) {
        QuestStatus prior = store.status(subject, quest.id());
        boolean alreadyParked = prior == QuestStatus.COMPLETED_UNCLAIMED;
        store.setStatus(subject, quest.id(), QuestStatus.COMPLETED);
        // Reported here rather than at each caller: this method is public precisely so a surface
        // outside the engine can close a quest out, and every path below returns from a different
        // place.
        store.markDirty(subject);
        Quest.Repeat repeat = quest.repeat();
        if (repeat == null) {
            return;
        }
        long nowMs = now();
        if (alreadyParked) {
            recordClaim(subject, quest);
        } else {
            recordCompletion(subject, quest, repeat, nowMs, true);
        }
        if (repeat.cooldownFrom() == Quest.Repeat.CooldownFrom.CLAIM) {
            store.setCooldownStamp(subject, quest.id(), nowMs);
        } else if (!alreadyParked) {
            // A COMPLETE-anchored clock that never parked (settled on the spot, an administrator) still starts
            // at the instant the objectives were met, which is this one.
            store.setCooldownStamp(subject, quest.id(), nowMs);
        }
    }

    /**
     * The ONE "park this finished quest for collection" rule: mark it waiting, record the completion
     * (the objectives were just met, whether or not anybody walks over to collect), and start a
     * {@code COMPLETE}-anchored cooldown here rather than at collection.
     *
     * <p>That anchor is what makes a quest belonging to a rotating, period-based offer behave: the
     * period it was FINISHED in is the one that counts, so collecting late does not burn a slot in
     * the new one. A {@code CLAIM}-anchored quest keeps the ordinary rule - its clock starts when the
     * player takes the reward.
     */
    public void markUnclaimed(@Nonnull Subject subject, @Nonnull Quest quest) {
        store.setStatus(subject, quest.id(), QuestStatus.COMPLETED_UNCLAIMED);
        // Reported here for the same reason as its peer above.
        store.markDirty(subject);
        Quest.Repeat repeat = quest.repeat();
        if (repeat == null) {
            return;
        }
        long nowMs = now();
        recordCompletion(subject, quest, repeat, nowMs, false);
        if (repeat.cooldownFrom() == Quest.Repeat.CooldownFrom.COMPLETE) {
            store.setCooldownStamp(subject, quest.id(), nowMs);
        }
    }

    /**
     * The writer of a FINISH, so the window roll-over rule lives in exactly one place: a completion
     * inside the same window as the last one adds to that window's tally, and a completion in a new
     * window starts the tally at one. Both LIFETIME tallies saturate rather than wrapping negative.
     *
     * <p>{@code claimedNow} says whether the reward went with it. A quest that pays out the instant
     * its objectives are met finishes and is collected in one moment; one that parks finishes here
     * and is collected later, through {@link #recordClaim}, which is the only other writer.
     */
    private void recordCompletion(@Nonnull Subject subject, @Nonnull Quest quest,
                                  @Nonnull Quest.Repeat repeat, long nowMs, boolean claimedNow) {
        QuestProgressStore.CompletionRecord prior = store.completions(subject, quest.id());
        Quest.Repeat.Reset reset = repeat.reset();
        int periodCount = 1;
        if (reset != null && RepeatPeriod.samePeriod(reset, prior.lastCompletionMs(), nowMs)) {
            periodCount = prior.periodCount() + 1;
        } else if (reset == null) {
            periodCount = 0;
        }
        int total = raised(prior.totalCount());
        int claimed = claimedNow ? raised(prior.claimedCount()) : prior.claimedCount();
        store.setCompletions(subject, quest.id(),
                new QuestProgressStore.CompletionRecord(nowMs, periodCount, total, claimed));
    }

    /**
     * The writer of a CLAIM on a finish that was already recorded: the player has come back for a
     * parked reward, so only the collected tally moves and the instant the objectives were met stays
     * exactly as it was.
     *
     * <p>It never has to look at the finish tally: the record clamps a collected count to it, so a
     * value that could not remember which of its finishes were still parked absorbs this collection
     * rather than counting it a second time.
     *
     * <p>The same clamp means an EMPTY prior record absorbs the collection entirely, since a
     * collected count cannot rise above a finish count of zero. <b>That is what an UPGRADE looks
     * like</b>, and it is the ordinary case rather than an exotic one: no released build ever wrote
     * a completion record, so a player arriving from one has none for any quest, and a reward they
     * parked before the upgrade pays out afterwards while this tally stays at zero. That is the
     * wanted answer - the run predates the tally entirely, so it neither counts nor over-counts, and
     * the count simply starts from their first finish after the upgrade. The same absorption covers
     * the one in-play route to it, a quest parked while it carried no {@link Quest.Repeat} group and
     * then given one before the player came back: recording the collection alone would claim a
     * payout for a run this record has no memory of.
     */
    private void recordClaim(@Nonnull Subject subject, @Nonnull Quest quest) {
        QuestProgressStore.CompletionRecord prior = store.completions(subject, quest.id());
        store.setCompletions(subject, quest.id(), new QuestProgressStore.CompletionRecord(
                prior.lastCompletionMs(), prior.periodCount(), prior.totalCount(),
                raised(prior.claimedCount())));
    }

    /** One more, unless the tally has already run out of room. */
    private static int raised(int tally) {
        return tally == Integer.MAX_VALUE ? Integer.MAX_VALUE : tally + 1;
    }

    /**
     * Give up an ACTIVE quest: its progress is discarded and it becomes offerable again.
     *
     * @return false when the player was not carrying it
     */
    public boolean abandon(@Nonnull Subject subject, @Nonnull String questId) {
        if (store.status(subject, questId) != QuestStatus.ACTIVE) {
            return false;
        }
        clearQuest(subject, questId);
        store.markDirty(subject);
        Quest quest = quests.get(questId);
        fireAbandoned(questId, subject, quest != null ? quest.tags() : List.of());
        return true;
    }

    /**
     * Re-arm one quest for this player: the store's own clear, plus the report every layer keyed on
     * that quest is owed ({@link QuestResets}).
     *
     * <p><b>This is the ONE way a quest is re-armed</b>, and the reason it is public rather than a
     * private helper: an authoring layer resetting a chained quest, and a rotating offer putting a
     * lapsed one back within reach, are re-arms exactly like an abandon. Reaching for
     * {@code store().clearQuest} directly re-arms the quest and tells nobody, which is a state
     * declared to die with the quest silently outliving it - the failure has no symptom until an
     * author's content stops behaving and nothing anywhere says why.
     *
     * <p>Whether the player was carrying it is the CALLER's question; this does the clear it was
     * asked for.
     *
     * <p>The clear REPORTS itself to the store, so a re-arm arriving from outside the engine - a
     * chained quest reset by its pool, a rotating offer putting a lapsed quest back - is saved like
     * any other write. A caller that also marks the player dirty around this simply says so twice,
     * which costs a backend one redundant look at a player it was already going to write.
     */
    public void clearQuest(@Nonnull Subject subject, @Nonnull String questId) {
        store.clearQuest(subject, questId);
        store.markDirty(subject);
        QuestResets.fire(subject, questId);
    }

    /**
     * An ADMINISTRATOR's reset of one quest: start this player over on it completely.
     *
     * <p>Two different things happen here, and collapsing them would lose one. The
     * {@link QuestProgressStore.CompletionRecord} goes, which an in-play re-arm deliberately never
     * does (a re-arm that wiped the record would leave a lifetime cap no player could ever reach)
     * and which is what "start over" means to an administrator. Then the quest is re-armed through
     * {@link #clearQuest}, so status, progress, cooldown stamp and pin go too and the re-arm is
     * REPORTED like any other: a memory a conversation was told to keep only as long as this quest
     * is forgotten with it. The record is dropped first so the one dirty mark covers both writes.
     */
    public void wipeQuest(@Nonnull Subject subject, @Nonnull String questId) {
        store.setCompletions(subject, questId, QuestProgressStore.CompletionRecord.NONE);
        clearQuest(subject, questId);
    }

    /**
     * An ADMINISTRATOR's reset of EVERY quest this player has any record of, each one wiped exactly
     * as {@link #wipeQuest} wipes it, record included and re-arm reported.
     *
     * <p>It reaches the ids the STORE knows. Anything else declared to die with "all quests" but
     * filed under an id this player never carried - a conversation can remember something about a
     * quest they never took - is the caller's to clear, and the shared dialogue engine offers the
     * quest-scoped sweep for exactly that.
     *
     * @return how many quest ids had a record to wipe
     */
    public int wipeAllQuests(@Nonnull Subject subject) {
        List<String> known = List.copyOf(store.knownQuestIds(subject));
        for (String questId : known) {
            wipeQuest(subject, questId);
        }
        return known.size();
    }

    /**
     * Idempotent per-player maintenance, safe to run on login and whenever a quest surface opens: a
     * finished repeatable that is off cooldown is reset to pristine, so what is stored agrees with
     * what every surface shows, and dead pins are reclaimed.
     *
     * <p>It is also where a carried quest's threshold steps are re-read (see
     * {@link #refreshStatThresholds}), so a value that moved while the player was away is caught the
     * moment they come back rather than waiting for their next unrelated action.
     *
     * <p><b>Deliberately non-destructive otherwise.</b> A quest whose definition has gone is LEFT
     * alone (it may come back), as is anything parked for collection (a reward may still be owed).
     * A re-arm keeps the player's {@link QuestProgressStore.CompletionRecord}, so a lifetime cap and
     * a calendar tally both survive it - that is the whole point of them.
     *
     * @return how many entries changed
     */
    public int selfHeal(@Nonnull Subject subject) {
        int changed = 0;
        for (String questId : List.copyOf(store.knownQuestIds(subject))) {
            Quest quest = quests.get(questId);
            if (quest == null || !quest.repeatable()) {
                continue;
            }
            if (store.status(subject, questId) != QuestStatus.COMPLETED) {
                continue;
            }
            if (!QuestLifecycle.repeatCheck(quest, subject, store, now()).available()) {
                continue;
            }
            clearQuest(subject, questId);
            changed++;
        }
        // Before the pin sweep, so a quest this settles gives its pin slot back in the same pass.
        changed += refreshStatThresholds(subject);
        changed += pruneStaleTracked(subject);
        if (changed > 0) {
            store.markDirty(subject);
        }
        return changed;
    }

    // ==================== Standing-value objectives ====================

    /**
     * Re-read every ACTIVE quest's threshold steps for this player. See
     * {@link #refreshStatThresholds(Subject, Quest)} for what one quest's pass does and why the
     * whole thing is a no-op when no factor vocabulary was wired.
     *
     * @return how many steps this call advanced
     */
    public int refreshStatThresholds(@Nonnull Subject subject) {
        if (statProbe == null) {
            return 0;
        }
        int advanced = 0;
        for (String questId : List.copyOf(store.knownQuestIds(subject))) {
            Quest quest = quests.get(questId);
            if (quest == null || store.status(subject, questId) != QuestStatus.ACTIVE) {
                continue;
            }
            advanced += refreshStatThresholds(subject, quest);
        }
        return advanced;
    }

    /**
     * Re-read ONE quest's {@link ObjectiveKindRegistry#STAT_THRESHOLD} steps and apply each reading
     * as a high-water value, settling the quest when that finishes it.
     *
     * <p>A threshold step describes a state rather than a moment, so nothing may ever fire to say it
     * was met. The engine therefore asks for itself at the three points where the answer can matter
     * and the cost is already paid: on {@link #accept} (folded into {@link #preSatisfiedFor}), in
     * {@link #selfHeal}, and off the back of a dispatch that moved this same quest. There is
     * deliberately NO poll behind it - an engine sweeping every carried quest on a timer is exactly
     * the design this avoids.
     *
     * <p>Only OUTSTANDING, unlocked steps are read, so an ordered quest's later steps cost nothing
     * until they are reachable and a finished one is never touched again. An unreadable value writes
     * nothing at all, which is why a channel that has gone missing cannot roll progress back.
     *
     * @return how many steps this call advanced
     */
    public int refreshStatThresholds(@Nonnull Subject subject, @Nonnull Quest quest) {
        if (statProbe == null) {
            return 0;
        }
        Map<String, ObjectiveProgressState> progress = null;
        int advanced = 0;
        boolean anyCompleted = false;
        for (ObjectiveDef objective : quest.objectives()) {
            if (!StatThresholdProbe.isStatThreshold(objective)
                    || !objectiveActive(subject, quest, objective.id())) {
                continue;
            }
            if (progress == null) {
                progress = progressOf(subject, quest.id());
            }
            ObjectiveKind kind = objectiveKinds.kind(objective.kind());
            ObjectiveProgressState state = progress.computeIfAbsent(objective.id(),
                    key -> ObjectiveArithmetic.fresh(kind, objective));
            if (state.isCompleted()) {
                continue;
            }
            int before = state.current();
            boolean justCompleted = ObjectiveArithmetic.applyStanding(kind, objective, state,
                    statProbe.valueFor(subject, objective));
            if (state.current() == before && !justCompleted) {
                continue;
            }
            advanced++;
            anyCompleted |= justCompleted;
            fireObjectiveProgressed(quest, objective, subject, state, justCompleted);
        }
        if (advanced > 0) {
            saveProgress(subject, quest.id(), progress);
            store.markDirty(subject);
        }
        if (anyCompleted) {
            checkCompletion(subject, quest);
        }
        return advanced;
    }

    // ==================== Visibility ====================

    /**
     * Should this quest be listed for this player before they take it? A quest they have ALREADY
     * started is always visible - somebody must be able to see what they are in the middle of.
     * Otherwise it must be switched on, not hidden, and past whatever prerequisite the consumer
     * gates it behind.
     */
    public boolean isVisible(@Nonnull Subject subject, @Nonnull Quest quest) {
        QuestStatus stored = store.status(subject, quest.id());
        if (stored != QuestStatus.NOT_STARTED) {
            return true;
        }
        if (!quest.available() || quest.visibility().hidden()) {
            return false;
        }
        return !quest.visibility().requirePrerequisites() || gates.prerequisitesMet(subject, quest);
    }

    /**
     * Should this quest be listed by the CHARACTER authored to hand it out? A different question
     * from {@link #isVisible}, and the difference is the whole point of the two.
     *
     * <p>{@link Quest.Visibility#hidden()} means "not on an open listing" - a browsable log, a book, any
     * surface enumerating the world's quests. At the quest's OWN giver there is no browsing going
     * on: the player walked up to the one character whose business this quest is, and hiding it
     * there leaves a character standing silently beside a quest they exist to hand out, with nothing
     * anywhere saying why. So a giver listing asks only whether the quest is switched on and whether
     * the player is past whatever the quest asks for first, which is exactly what an author means by
     * "keep this out of sight until it is relevant".
     *
     * <p>A quest the player has already started is listed either way, for the same reason it is
     * visible: somebody must be able to see what they are in the middle of.
     */
    public boolean isOfferable(@Nonnull Subject subject, @Nonnull Quest quest) {
        QuestStatus stored = store.status(subject, quest.id());
        if (stored != QuestStatus.NOT_STARTED) {
            return true;
        }
        if (!quest.available()) {
            return false;
        }
        return !quest.visibility().requirePrerequisites() || gates.prerequisitesMet(subject, quest);
    }

    // ==================== Tracking ====================

    /**
     * Pin a quest to the player's tracker. Dead pins are reclaimed first, so the cap is measured
     * against live ones and a player looking at two pinned quests is never told they already have
     * the maximum.
     *
     * @return false when the id is unknown or the player is already at the cap
     */
    public boolean track(@Nonnull Subject subject, @Nonnull String questId) {
        if (!quests.containsKey(questId)) {
            return false;
        }
        pruneStaleTracked(subject);
        Map<String, Long> pins = store.trackedPins(subject);
        boolean fresh = !pins.containsKey(questId);
        if (fresh && pins.size() >= maxTracked) {
            return false;
        }
        store.setTrackedPin(subject, questId, now());
        store.markDirty(subject);
        // A pin that was already there is merely re-stamped: nothing about what the player is
        // watching changed, so nothing is announced.
        if (fresh) {
            fireTracked(questId, subject, true);
        }
        return true;
    }

    /**
     * Unpin a quest. Returns true when a pin was actually there.
     *
     * <p>A pin is a display preference rather than progress, but it is SAVED state like any other,
     * so a store that batches its writes is told about it. A store keeping nothing of its own reads
     * that as the no-op it inherits.
     */
    public boolean untrack(@Nonnull Subject subject, @Nonnull String questId) {
        if (!store.clearTrackedPin(subject, questId)) {
            return false;
        }
        store.markDirty(subject);
        fireTracked(questId, subject, false);
        return true;
    }

    /** The player's pinned quest ids, oldest pin first. */
    @Nonnull
    public List<String> tracked(@Nonnull Subject subject) {
        List<Map.Entry<String, Long>> pins = new ArrayList<>(store.trackedPins(subject).entrySet());
        pins.sort(Comparator.comparingLong(Map.Entry::getValue));
        List<String> out = new ArrayList<>(pins.size());
        for (Map.Entry<String, Long> pin : pins) {
            out.add(pin.getKey());
        }
        return out;
    }

    /** The pinned quests that are still being carried, capped at {@link #maxTracked()}. */
    @Nonnull
    public List<Quest> trackedActive(@Nonnull Subject subject) {
        List<Quest> out = new ArrayList<>();
        for (String questId : tracked(subject)) {
            if (!isActive(subject, questId)) {
                continue;
            }
            Quest quest = quests.get(questId);
            if (quest == null) {
                continue;
            }
            out.add(quest);
            if (out.size() >= maxTracked) {
                break;
            }
        }
        return out;
    }

    /**
     * Drop pins for quests that are no longer being carried. A tracker only shows active quests, so
     * a finished or abandoned one leaves a pin that shows nothing yet still occupies a slot; left
     * alone, enough of them fill every slot and block new pinning while the tracker looks empty. A
     * pin is a display preference, not progress, so dropping a dead one loses nothing.
     *
     * @return how many were dropped
     */
    public int pruneStaleTracked(@Nonnull Subject subject) {
        int dropped = 0;
        for (String questId : store.trackedPins(subject).keySet()) {
            if (!isActive(subject, questId) && store.clearTrackedPin(subject, questId)) {
                dropped++;
                // Announced per pin rather than once per sweep: a tracker keyed on the quest id
                // learns exactly which quest left, and a sweep that dropped nothing says nothing.
                fireTracked(questId, subject, false);
            }
        }
        if (dropped > 0) {
            store.markDirty(subject);
        }
        return dropped;
    }

    /** How many of this quest's objectives are done. Total is at least 1, so a bar can divide by it. */
    @Nonnull
    public ObjectiveTally tally(@Nonnull Subject subject, @Nonnull Quest quest) {
        Map<String, ObjectiveProgressState> progress = progressOf(subject, quest.id());
        int completed = 0;
        for (ObjectiveDef objective : quest.objectives()) {
            ObjectiveProgressState state = progress.get(objective.id());
            if (state != null && state.isCompleted()) {
                completed++;
            }
        }
        return new ObjectiveTally(completed, Math.max(1, quest.objectives().size()));
    }

    // ==================== Progress access ====================

    /** This player's progress on one quest, keyed by objective id. Empty when they have none. */
    @Nonnull
    public Map<String, ObjectiveProgressState> progressOf(@Nonnull Subject subject,
                                                          @Nonnull String questId) {
        return QuestProgressPayload.deserialize(store.progressPayload(subject, questId));
    }

    /** This player's progress on one objective, or null when there is none recorded. */
    @Nullable
    public ObjectiveProgressState progressOf(@Nonnull Subject subject, @Nonnull String questId,
                                             @Nonnull String objectiveId) {
        return progressOf(subject, questId).get(objectiveId);
    }

    /**
     * Write this player's progress back, KEEPING whatever place the quest was taken at. The site is
     * re-read here rather than threaded through every caller, because a save that forgot it would
     * quietly un-bind the quest from its place on the next step the player took, and nothing would
     * report it. {@link #accept} is the one caller that supplies a site of its own.
     */
    private void saveProgress(@Nonnull Subject subject, @Nonnull String questId,
                              @Nonnull Map<String, ObjectiveProgressState> progress) {
        saveProgress(subject, questId, progress, acceptSiteOf(subject, questId));
    }

    private void saveProgress(@Nonnull Subject subject, @Nonnull String questId,
                              @Nonnull Map<String, ObjectiveProgressState> progress,
                              @Nullable String acceptSite) {
        store.putProgressPayload(subject, questId,
                QuestProgressPayload.serialize(progress, acceptSite));
    }

    // ==================== The narrow read seam ====================

    // Every method below is the id-keyed face of a read that already exists above, and together they
    // are the whole of QuestStateReader. They live in one block on purpose: it is what a surface
    // holding only the seam can do, so what that surface can do stays readable in one screen.

    /**
     * {@inheritDoc}
     *
     * <p>The id-keyed form of {@link #status(Subject, Quest)}. An id this engine does not carry reads
     * as {@link QuestStatus#NOT_STARTED} rather than throwing, so a condition written against a quest
     * that has since been removed hides nothing.
     */
    @Override
    @Nonnull
    public QuestStatus status(@Nonnull Subject subject, @Nonnull String questId) {
        Quest quest = quest(questId);
        return quest == null ? QuestStatus.NOT_STARTED : status(subject, quest);
    }

    /** {@inheritDoc} The id-keyed name for {@link #progressOf(Subject, String, String)}. */
    @Override
    @Nullable
    public ObjectiveProgressState objectiveProgress(@Nonnull Subject subject, @Nonnull String questId,
                                                    @Nonnull String objectiveId) {
        return progressOf(subject, questId, objectiveId);
    }

    /** {@inheritDoc} The id-only view of {@link #activeAndUnclaimed(Subject)}. */
    @Override
    @Nonnull
    public List<String> activeAndUnclaimedIds(@Nonnull Subject subject) {
        List<String> out = new ArrayList<>();
        for (Quest quest : activeAndUnclaimed(subject)) {
            out.add(quest.id());
        }
        return out;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The id-keyed form of {@link #canDeliverTurnInAt(Subject, Quest, String)}. An unknown id
     * fails closed: this is a positive gate, and opening one on a typo offers a hand-in that cannot
     * happen.
     */
    @Override
    public boolean canDeliverTurnInAt(@Nonnull Subject subject, @Nonnull String questId,
                                      @Nullable String atId) {
        Quest quest = quest(questId);
        return quest != null && canDeliverTurnInAt(subject, quest, atId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The id-keyed form of {@link #readyToTurnInAt(Subject, Quest, String)}, which is exactly the
     * weaker question this method names: where the player should GO, whether or not anything changes
     * hands when they arrive. The inherited default cannot tell those apart and answers the stricter
     * one, so an engine that can, answers for itself - otherwise a step that says "go and speak to
     * them" would leave the character it points at unmarked.
     *
     * <p>An unknown id fails closed, like every other readiness read here.
     */
    @Override
    public boolean resolvesTurnInAt(@Nonnull Subject subject, @Nonnull String questId,
                                    @Nullable String atId) {
        Quest quest = quest(questId);
        return quest != null && readyToTurnInAt(subject, quest, atId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The id-keyed form of {@link #canCompleteAt(Subject, Quest, String)}. An id this engine does
     * not carry has no site rule to break, so it reads TRUE - the opposite of the readiness reads
     * beside it, and deliberately: this is a REFUSAL gate whose neutral answer is yes, and answering
     * no would hide the claim on every quest a surface asked about that this engine happens not to
     * own. Whether the quest exists at all is answered by the reads that name it.
     */
    @Override
    public boolean canCompleteAt(@Nonnull Subject subject, @Nonnull String questId,
                                 @Nullable String atId) {
        Quest quest = quest(questId);
        return quest == null || canCompleteAt(subject, quest, atId);
    }

    /** {@inheritDoc} Walks what the player is carrying, so an empty log answers immediately. */
    @Override
    public boolean hasDeliverableTurnInAt(@Nonnull Subject subject, @Nullable String atId) {
        for (Quest quest : activeAndUnclaimed(subject)) {
            if (canDeliverTurnInAt(subject, quest, atId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The id-keyed form of {@link #settlesTurnInAt(Subject, Quest, String)}. An unknown id fails
     * closed, like every other readiness read here.
     */
    @Override
    public boolean settlesTurnInAt(@Nonnull Subject subject, @Nonnull String questId,
                                   @Nullable String atId) {
        Quest quest = quest(questId);
        return quest != null && settlesTurnInAt(subject, quest, atId);
    }

    /** {@inheritDoc} Walks what the player is carrying, so an empty log answers immediately. */
    @Override
    public boolean hasSettleableTurnInAt(@Nonnull Subject subject, @Nullable String atId) {
        for (Quest quest : activeAndUnclaimed(subject)) {
            if (settlesTurnInAt(subject, quest, atId)) {
                return true;
            }
        }
        return false;
    }

    // ==================== Internals ====================

    @Nonnull
    private RewardGrants.GrantOutcome grantRewards(@Nonnull Subject subject, @Nonnull Quest quest) {
        return RewardGrants.grantAll(quest.rewards(), subject, "quest:" + quest.id(), rewardKinds,
                rewardRetryQueue, warn);
    }

    /**
     * How much of this objective the player has ALREADY satisfied at the instant they take the
     * quest, from both sources of that answer: the consumer's own
     * {@link QuestGates#preSatisfiedAmount} and, for a threshold step, the engine's own reading of
     * the stat channel it names.
     *
     * <p><b>They fold as a maximum, and that is not a tie-break.</b> Both are high-water values
     * applied through the same {@link ObjectiveProgressState#applyValue} path, where applying two in
     * turn leaves exactly the larger of them. Taking the max up front is therefore the same result
     * in one write, which is why there is one seeding path here rather than two.
     *
     * <p><b>Ordering is deliberately not consulted here</b>, which is what the gate's answer has
     * always done: a value the player is already standing at is true whichever step the quest is on,
     * and refusing to record it would only ask them to go and reach it again. The later re-check
     * path DOES honour ordering, because there it is a question of whether to spend a read at all.
     */
    private long preSatisfiedFor(@Nonnull Subject subject, @Nonnull Quest quest,
                                 @Nonnull ObjectiveDef objective) {
        long gated;
        try {
            gated = gates.preSatisfiedAmount(subject, quest, objective);
        } catch (Throwable t) {
            warn.accept("pre-satisfied check failed for '" + quest.id() + "/" + objective.id()
                    + "': " + t.getMessage());
            gated = 0L;
        }
        if (statProbe == null || !StatThresholdProbe.isStatThreshold(objective)) {
            return gated;
        }
        return Math.max(gated, statProbe.valueFor(subject, objective));
    }

    private void fireAccepted(@Nonnull Quest quest, @Nonnull Subject subject) {
        if (nativeEvents) {
            QuestEvents.fireAccepted(quest.id(), subject.id(), quest.tags());
        }
    }

    private void fireObjectiveProgressed(@Nonnull Quest quest, @Nonnull ObjectiveDef objective,
                                         @Nonnull Subject subject,
                                         @Nonnull ObjectiveProgressState state, boolean justCompleted) {
        if (nativeEvents) {
            QuestEvents.fireObjectiveProgressed(quest.id(), objective.id(), subject.id(),
                    state.current(), state.required(), justCompleted, quest.tags());
        }
        // The two SENTENCES are deferred: this moment is announced on every tick of every tracked
        // objective, so on every block broken and every mob killed, and composing a step's wording
        // for a moment nobody authored would be the most expensive thing on that path. A supplier
        // is asked for only once a hook says it answers this moment.
        ProgressionFeedbackHook.fire(feedbackHook, warn, "Quest_Objective_Progressed", subject,
                "quest", quest.id(),
                "title", (Supplier<?>) () -> quest.text().titleOr(quest.id()),
                "objective", objective.id(),
                "step", (Supplier<?>) () -> quest.text().objective(objective.id()),
                "current", Integer.valueOf(state.current()),
                "required", Integer.valueOf(state.required()),
                "finished", Boolean.valueOf(justCompleted));
    }

    /**
     * Every objective is met.
     *
     * <p>Two moment ids rather than one flag on a single moment, because they are two different
     * things to say: a quest that paid out is finished, a quest that PARKED is waiting to be
     * collected somewhere, and a server author writing the second one wants their own words and
     * their own sound for it rather than a variant of the first.
     *
     * <p>Both carry {@code parked} and, when the quest names one, {@code turnIn} (the kind of place
     * it is collected at, {@code character} or {@code accept_site}); a parked one also carries
     * {@code reason} ({@link #PARKED_COLLECT} / {@link #PARKED_NO_SPACE} / {@link #PARKED_AWAY}),
     * so ONE authored file can say "your bags are full" and "collect it where you took it" as two
     * cases of the same moment.
     *
     * <p>Both also carry the quest's whole payout under {@code rewards} - deferred, like the
     * sentences on the progress moment, so a moment nobody authored never composes it - which is
     * what lets an authored toast list what was (or waits to be) handed over.
     *
     * @param parkedReason why it parked, or null for a quest paying out now
     */
    private void fireCompleted(@Nonnull Quest quest, @Nonnull Subject subject,
                               @Nullable String parkedReason) {
        boolean parked = parkedReason != null;
        if (nativeEvents) {
            QuestEvents.fireCompleted(quest.id(), subject.id(), parked, quest.tags());
        }
        ProgressionFeedbackHook.fire(feedbackHook, warn, parked ? "Quest_Parked" : "Quest_Completed",
                subject, "quest", quest.id(), "title", quest.text().titleOr(quest.id()),
                // Carried on BOTH ids, so a hook handed either one can tell which case it is
                // without reading meaning into the id it was called with.
                "parked", Boolean.valueOf(parked),
                "reason", parkedReason,
                "turnIn", turnInToken(quest),
                "rewards", (Supplier<?>) quest::rewards);
    }

    /**
     * The rewards were paid, either the instant the quest finished or when the player came to
     * collect them; {@code collected} tells the two apart, so a jingle authored for collecting a
     * parked reward does not also play over the completion jingle of one that settled on the spot.
     * The list just paid rides under {@code rewards}, deferred like the completion moment's.
     */
    private void fireClaimed(@Nonnull Quest quest, @Nonnull Subject subject,
                             @Nonnull RewardGrants.GrantOutcome outcome, boolean collected) {
        if (nativeEvents) {
            QuestEvents.fireClaimed(quest.id(), subject.id(), outcome.granted(), outcome.queued(),
                    outcome.failed(), quest.tags());
        }
        ProgressionFeedbackHook.fire(feedbackHook, warn, "Quest_Claimed", subject,
                "quest", quest.id(), "title", quest.text().titleOr(quest.id()),
                "collected", Boolean.valueOf(collected),
                "granted", Integer.valueOf(outcome.granted()),
                "queued", Integer.valueOf(outcome.queued()),
                "failed", Integer.valueOf(outcome.failed()),
                "rewards", (Supplier<?>) quest::rewards);
    }

    /**
     * The kind of place this quest is collected at, as the lower-case token a moment carries under
     * {@code turnIn}, or null for a quest collected from anywhere (which is then omitted).
     */
    @Nullable
    private static String turnInToken(@Nonnull Quest quest) {
        QuestTurnInSite site = quest.turnInAt();
        return site == null ? null : site.kind().name().toLowerCase(Locale.ROOT);
    }

    private void fireAbandoned(@Nonnull String questId, @Nonnull Subject subject,
                               @Nonnull List<String> tags) {
        if (nativeEvents) {
            QuestEvents.fireAbandoned(questId, subject.id(), tags);
        }
    }

    /**
     * A pin changed. The pin write paths hold only an id, so the quest is looked up for its tags
     * here; a pin on an id the catalogue no longer carries (a stale one being swept) rides with
     * none rather than being dropped from the announcement.
     */
    private void fireTracked(@Nonnull String questId, @Nonnull Subject subject, boolean tracked) {
        if (nativeEvents) {
            Quest quest = quests.get(questId);
            QuestEvents.fireTracked(questId, subject.id(), tracked,
                    quest != null ? quest.tags() : List.of());
        }
    }

    /** Report an authoring mistake that would otherwise repeat on every event exactly once. */
    private void warnOnce(@Nonnull String key, @Nonnull String message) {
        if (warnedOnce.add(key)) {
            warn.accept(message);
        }
    }

    // ==================== Builder ====================

    /**
     * Assembles a {@link QuestEngine}. Every seam has a working default, so the smallest useful
     * engine is {@code QuestEngine.builder().build()} - an in-memory store, the built-in objective
     * vocabulary, no rewards, no gates, no inventory.
     */
    public static final class Builder {

        @Nullable private ObjectiveKindRegistry objectiveKinds;
        @Nullable private RewardKindRegistry rewardKinds;
        @Nullable private QuestProgressStore store;
        private QuestPossessionProbe possession = QuestPossessionProbe.NONE;
        private QuestInventoryConsumer inventory = QuestInventoryConsumer.NONE;
        private QuestGates gates = QuestGates.OPEN;
        private ProgressionSystemGate systemGate = ProgressionSystemGate.OPEN;
        private ProgressDispatchTap tap = ProgressDispatchTap.NONE;
        @Nullable private FactorRegistry factors;
        @Nullable private Function<Subject, FactorContext> factorContext;
        private QuestI18n i18n = QuestI18n.NONE;
        @Nullable private BiConsumer<Subject, String> rewardRetryQueue;
        private ProgressionFeedbackHook feedbackHook = ProgressionFeedbackHook.NONE;
        private Consumer<String> warn = DEFAULT_WARN;
        private LongSupplier clock = System::currentTimeMillis;
        private int maxTracked = 5;
        private IntSupplier maxActive = NO_CAP;
        private boolean nativeEvents = true;

        Builder() {
        }

        /** The objective vocabulary content is authored against. Defaults to the built-ins alone. */
        @Nonnull
        public Builder objectiveKinds(@Nullable ObjectiveKindRegistry objectiveKinds) {
            this.objectiveKinds = objectiveKinds;
            return this;
        }

        /** The reward vocabulary. Defaults to empty, which grants nothing and says so. */
        @Nonnull
        public Builder rewardKinds(@Nullable RewardKindRegistry rewardKinds) {
            this.rewardKinds = rewardKinds;
            return this;
        }

        /** Where per-player state lives. Defaults to an in-memory store that dies with the process. */
        @Nonnull
        public Builder store(@Nullable QuestProgressStore store) {
            this.store = store;
            return this;
        }

        /** How to ask whether a player is carrying something. Defaults to refusing everything. */
        @Nonnull
        public Builder possessionProbe(@Nonnull QuestPossessionProbe possession) {
            this.possession = possession;
            return this;
        }

        /**
         * As {@link #possessionProbe(QuestPossessionProbe)}, from a subject-blind predicate - for a
         * consumer whose inventory access is already bound to one player.
         */
        @Nonnull
        public Builder possessionProbe(@Nonnull BiPredicate<String, Integer> possession) {
            return possessionProbe(QuestPossessionProbe.ofPredicate(possession));
        }

        /** How to actually take hand-in items. Defaults to taking nothing. */
        @Nonnull
        public Builder inventoryConsumer(@Nonnull QuestInventoryConsumer inventory) {
            this.inventory = inventory;
            return this;
        }

        /** The consumer's say on accepting, seeing, and being paid. Defaults to no gates. */
        @Nonnull
        public Builder gates(@Nonnull QuestGates gates) {
            this.gates = gates;
            return this;
        }

        /**
         * The owner's "quests are switched off for this player" switch, asked before an accept and
         * before the join-time auto-accept. The shared runtime hands in its COMPOSED gate (every
         * registered owner switch, ANDed) so an accept and a produced moment are refused by the same
         * answer. Defaults to {@link ProgressionSystemGate#OPEN}: an engine nobody switched off.
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
         * The factor vocabulary the engine reads a standing value through, which is what lets it
         * settle a {@link ObjectiveKindRegistry#STAT_THRESHOLD} step for itself rather than waiting
         * for a producer to fire one. Unset leaves that kind purely consumer-fired, exactly like
         * every other kind in the vocabulary.
         *
         * <p>Pair it with {@link #factorContext}: the portable stat reading needs the entity it is
         * being asked about, so a registry with no way to reach one resolves nothing and writes
         * nothing. Wired the same way a gate evaluator's pair is, and usually with the same two
         * values.
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

        /** The naming seam for quest text. Defaults to no keys resolving. */
        @Nonnull
        public Builder i18n(@Nonnull QuestI18n i18n) {
            this.i18n = i18n;
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

        /** The clock, in epoch milliseconds. Inject one to exercise cooldown boundaries. */
        @Nonnull
        public Builder clock(@Nonnull LongSupplier clock) {
            this.clock = clock;
            return this;
        }

        /** How many quests a player may pin. Defaults to 5; values below 1 are raised to 1. */
        @Nonnull
        public Builder maxTracked(int maxTracked) {
            this.maxTracked = Math.max(1, maxTracked);
            return this;
        }

        /** How many quests a player may carry at once. Defaults to 0, meaning no limit. */
        @Nonnull
        public Builder maxActive(int maxActive) {
            int fixed = Math.max(0, maxActive);
            this.maxActive = () -> fixed;
            return this;
        }

        /**
         * The same cap read LIVE, for a consumer whose limit lives in a config an owner reloads. The
         * consumer supplies the number; the {@code log_full} refusal built on it stays the engine's.
         */
        @Nonnull
        public Builder maxActive(@Nullable IntSupplier maxActive) {
            this.maxActive = maxActive == null ? NO_CAP : maxActive;
            return this;
        }

        /**
         * Whether quest moments are published on the shared engine event bus. On by default; turn it
         * off for an engine whose moments are nobody else's business.
         */
        @Nonnull
        public Builder nativeEvents(boolean nativeEvents) {
            this.nativeEvents = nativeEvents;
            return this;
        }

        @Nonnull
        public QuestEngine build() {
            return new QuestEngine(this);
        }
    }

    /** Default warn sink: the library logger, guarded so a log-manager-less test JVM cannot crash. */
    private static final Consumer<String> DEFAULT_WARN = message -> SafeLog.warn("[quest] " + message);
}
