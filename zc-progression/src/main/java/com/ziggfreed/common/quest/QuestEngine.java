package com.ziggfreed.common.quest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorRegistry;
import com.ziggfreed.common.loot.reward.RewardGrants;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.progress.DispatchOptions;
import com.ziggfreed.common.progress.MatchFlavor;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.ObjectiveIndex;
import com.ziggfreed.common.progress.ObjectiveKind;
import com.ziggfreed.common.progress.ObjectiveKindRegistry;
import com.ziggfreed.common.progress.ObjectiveMatch;
import com.ziggfreed.common.progress.ObjectiveProgressState;
import com.ziggfreed.common.progress.ProgressDispatchTap;
import com.ziggfreed.common.progress.StatThresholdProbe;
import com.ziggfreed.common.progress.ZoneRef;
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
    private final MatchFlavor matchFlavor;
    private final QuestPossessionProbe possession;
    private final QuestInventoryConsumer inventory;
    private final QuestGates gates;
    private final ProgressDispatchTap tap;
    /** Null when no factor vocabulary was wired, which switches every threshold re-check off. */
    @Nullable private final StatThresholdProbe statProbe;
    private final QuestI18n i18n;
    @Nullable private final BiConsumer<Subject, String> rewardRetryQueue;
    private final Consumer<String> warn;
    private final LongSupplier clock;
    private final int maxTracked;
    private final int maxActive;
    private final boolean nativeEvents;

    /** The catalogue and its index, replaced together so a dispatch never sees a half-loaded pair. */
    private volatile Map<String, Quest> quests = Map.of();
    private volatile ObjectiveIndex index = ObjectiveIndex.EMPTY;

    /** Authoring mistakes that would otherwise repeat on every event, reported once per case. */
    private final Set<String> warnedOnce = ConcurrentHashMap.newKeySet();

    private QuestEngine(@Nonnull Builder b) {
        this.objectiveKinds = b.objectiveKinds != null ? b.objectiveKinds : new ObjectiveKindRegistry();
        this.rewardKinds = b.rewardKinds != null ? b.rewardKinds : new RewardKindRegistry();
        this.store = b.store != null ? b.store : new InMemoryQuestProgressStore();
        this.matchFlavor = b.matchFlavor;
        this.possession = b.possession;
        this.inventory = b.inventory;
        this.gates = b.gates;
        this.tap = b.tap;
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

    /** Which matching dialect this engine runs. */
    @Nonnull
    public MatchFlavor matchFlavor() {
        return matchFlavor;
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

    /** How many quests a player may carry at once, or {@code 0} for no limit. */
    public int maxActive() {
        return maxActive;
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
     * May this player take this quest, and if not, why not? Checks the mechanical rules the engine
     * owns (available, not already started, off cooldown, room in the log) and then asks the
     * consumer's {@link QuestGates}, keeping every reason from both.
     */
    @Nonnull
    public AcceptCheck canAccept(@Nonnull Subject subject, @Nonnull Quest quest) {
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
        if (maxActive > 0 && activeCount(subject) >= maxActive) {
            reasons.add(QuestGates.REASON_LOG_FULL);
        }
        if (quest.visibility().requirePrerequisites() && !gates.prerequisitesMet(subject, quest)) {
            reasons.add(QuestGates.REASON_PREREQUISITES);
        }
        if (!gates.accepts(subject, quest, reasons) && reasons.isEmpty()) {
            reasons.add(QuestGates.REASON_PREREQUISITES);
        }
        return reasons.isEmpty() ? AcceptCheck.ALLOWED : new AcceptCheck(false, List.copyOf(reasons));
    }

    /**
     * Take the quest on for this player: mark it active, seed every objective's progress, apply
     * whatever is already satisfied (see {@link #preSatisfiedFor}), and pin it if the quest asks to
     * be pinned.
     *
     * <p>Does NOT itself check eligibility beyond the cooldown - call {@link #canAccept} first when
     * the player is choosing. Callers that deliberately force a quest on somebody (a scripted start,
     * an administrator) skip the check on purpose, so it is not baked in here.
     *
     * @return false only when a repeatable quest's own repeat rules refuse it
     */
    public boolean accept(@Nonnull Subject subject, @Nonnull Quest quest) {
        if (quest.repeatable()
                && !QuestLifecycle.repeatCheck(quest, subject, store, now()).available()) {
            return false;
        }
        store.setStatus(subject, quest.id(), QuestStatus.ACTIVE);

        Map<String, ObjectiveProgressState> progress = new LinkedHashMap<>();
        for (ObjectiveDef objective : quest.objectives()) {
            ObjectiveProgressState state = new ObjectiveProgressState(0, objective.amountAsInt());
            long preSatisfied = preSatisfiedFor(subject, quest, objective);
            if (preSatisfied > 0) {
                state.applyValue(preSatisfied);
            }
            progress.put(objective.id(), state);
        }
        saveProgress(subject, quest.id(), progress);

        if (quest.autoTrack()) {
            track(subject, quest.id());
        }
        store.markDirty(subject);
        fireAccepted(quest, subject);
        return true;
    }

    /**
     * Accept every available auto-accept quest this player is eligible for and not already past,
     * then immediately settle any whose objectives are already met. Call once when a player becomes
     * ready, after {@link #selfHeal}, so a repeatable that has come back around reads offerable.
     *
     * @return how many were accepted
     */
    public int autoAcceptAvailable(@Nonnull Subject subject) {
        int accepted = 0;
        for (Quest quest : quests.values()) {
            if (!quest.autoAccept() || !quest.available()) {
                continue;
            }
            if (status(subject, quest) != QuestStatus.NOT_STARTED) {
                continue;
            }
            if (!canAccept(subject, quest).allowed() || !accept(subject, quest)) {
                continue;
            }
            checkCompletion(subject, quest);
            accepted++;
        }
        return accepted;
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
            if (!objective.matches(matchFlavor, target, qualifier) || !objective.matchesZone(zone)) {
                continue;
            }
            if (!objectiveActive(subject, quest, objective.id())) {
                continue;
            }

            Map<String, ObjectiveProgressState> progress = progressOf(subject, quest.id());
            ObjectiveProgressState state = progress.computeIfAbsent(objective.id(),
                    key -> new ObjectiveProgressState(0, objective.amountAsInt()));
            if (state.isCompleted()) {
                continue;
            }
            int before = state.current();
            boolean justCompleted = kind.valueBased() ? state.applyValue(amount) : state.advance(amount);
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
     * outstanding objective that names this place as its target.
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
            return ObjectiveMatch.targetMatches(matchFlavor, objective.target(), objective.matchMode(), atId);
        }
        return false;
    }

    /**
     * {@link #readyToTurnInAt} AND the player is actually carrying what the step asks for. Use this
     * to OFFER a hand-in, so an offer cannot be shown and then silently do nothing; use the looser
     * {@link #readyToTurnInAt} for listing and ranking, which deliberately allows partial delivery.
     *
     * <p>A step with nothing to deliver (a report-back hand-in with a blank target, or a
     * talk-to-this-place step) is deliverable with an empty inventory. An item hand-in needs the
     * WHOLE remaining amount: offering it should mean the step finishes.
     */
    public boolean canDeliverTurnInAt(@Nonnull Subject subject, @Nonnull Quest quest,
                                      @Nullable String atId) {
        if (!readyToTurnInAt(subject, quest, atId)) {
            return false;
        }
        ObjectiveDef objective = firstActiveTurnIn(subject, quest, atId);
        if (objective == null) {
            return true;
        }
        String itemId = objective.target();
        if (itemId.isEmpty()) {
            return true;
        }
        int remaining = remainingFor(subject, quest, objective);
        return remaining <= 0 || possession.holds(subject, itemId, remaining);
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
                key -> new ObjectiveProgressState(0, objective.amountAsInt()));
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
            checkCompletion(subject, quest);
        }
        return credited;
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

    // ==================== Completion, claim, close-out ====================

    /**
     * Settle a quest whose objectives may now all be met: nothing happens unless every objective is
     * complete, and then the quest either pays out or parks for the player to collect.
     *
     * <p>It parks when the quest asks to be collected somewhere ({@link Quest#autoClaim()} off) or
     * when the consumer says the player cannot receive the rewards right now (a full inventory) -
     * the second case being what stops a payout from vanishing into nowhere.
     */
    public void checkCompletion(@Nonnull Subject subject, @Nonnull Quest quest) {
        if (store.status(subject, quest.id()) != QuestStatus.ACTIVE) {
            return;
        }
        if (!allObjectivesComplete(subject, quest)) {
            return;
        }
        boolean canReceive = gates.canReceiveRewards(subject, quest);
        if (!quest.autoClaim() || !canReceive) {
            markUnclaimed(subject, quest);
            store.markDirty(subject);
            fireCompleted(quest, subject, true);
            return;
        }
        markCompleted(subject, quest);
        fireCompleted(quest, subject, false);
        RewardGrants.GrantOutcome outcome = grantRewards(subject, quest);
        store.markDirty(subject);
        fireClaimed(quest, subject, outcome);
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
     * Collect a parked quest's rewards. Refuses unless the quest really is waiting to be collected
     * and the consumer says the player can receive them.
     *
     * @return true when the rewards were granted
     */
    public boolean claim(@Nonnull Subject subject, @Nonnull Quest quest) {
        if (store.status(subject, quest.id()) != QuestStatus.COMPLETED_UNCLAIMED) {
            return false;
        }
        if (!gates.canReceiveRewards(subject, quest)) {
            return false;
        }
        markCompleted(subject, quest);
        RewardGrants.GrantOutcome outcome = grantRewards(subject, quest);
        // Collecting is a player-owned transaction boundary, so the writes are committed here.
        store.markDirty(subject);
        store.flush(subject);
        fireClaimed(quest, subject, outcome);
        return true;
    }

    /**
     * Close a quest out and pay it, regardless of whether its objectives were met - for a scripted
     * skip or an administrator.
     *
     * <p>Idempotent for a one-shot quest already finished: it is left alone and NOT paid twice, so a
     * double click cannot double-grant.
     *
     * @return true when this call closed the quest out
     */
    public boolean forceComplete(@Nonnull Subject subject, @Nonnull Quest quest) {
        if (store.status(subject, quest.id()) == QuestStatus.COMPLETED && !quest.repeatable()) {
            return false;
        }
        markCompleted(subject, quest);
        fireCompleted(quest, subject, false);
        RewardGrants.GrantOutcome outcome = grantRewards(subject, quest);
        store.markDirty(subject);
        fireClaimed(quest, subject, outcome);
        return true;
    }

    /**
     * The ONE "this quest is finished" rule: set the terminal status, record the completion unless
     * it was already recorded when the quest parked, and start a {@code CLAIM}-anchored cooldown.
     *
     * <p>Reading the PRIOR status is what tells the two apart with no bookkeeping flag anywhere. A
     * prior {@link QuestStatus#COMPLETED_UNCLAIMED} means this call is the collect of an
     * already-parked quest, so the completion is on the record already and the clock, if it is
     * anchored to {@code COMPLETE}, was started back then. Anything else - the auto-claim path, an
     * administrator, a scripted skip - is the moment the quest finished.
     */
    public void markCompleted(@Nonnull Subject subject, @Nonnull Quest quest) {
        QuestStatus prior = store.status(subject, quest.id());
        boolean alreadyParked = prior == QuestStatus.COMPLETED_UNCLAIMED;
        store.setStatus(subject, quest.id(), QuestStatus.COMPLETED);
        Quest.Repeat repeat = quest.repeat();
        if (repeat == null) {
            return;
        }
        long nowMs = now();
        if (!alreadyParked) {
            recordCompletion(subject, quest, repeat, nowMs);
        }
        if (repeat.cooldownFrom() == Quest.Repeat.CooldownFrom.CLAIM) {
            store.setCooldownStamp(subject, quest.id(), nowMs);
        } else if (!alreadyParked) {
            // A COMPLETE-anchored clock that never parked (auto-claim, an administrator) still starts
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
        Quest.Repeat repeat = quest.repeat();
        if (repeat == null) {
            return;
        }
        long nowMs = now();
        recordCompletion(subject, quest, repeat, nowMs);
        if (repeat.cooldownFrom() == Quest.Repeat.CooldownFrom.COMPLETE) {
            store.setCooldownStamp(subject, quest.id(), nowMs);
        }
    }

    /**
     * The ONE writer of a {@link QuestProgressStore.CompletionRecord}, so the window roll-over rule
     * lives in exactly one place: a completion inside the same window as the last one adds to that
     * window's tally, and a completion in a new window starts the tally at one. The lifetime tally
     * saturates rather than wrapping negative.
     */
    private void recordCompletion(@Nonnull Subject subject, @Nonnull Quest quest,
                                  @Nonnull Quest.Repeat repeat, long nowMs) {
        QuestProgressStore.CompletionRecord prior = store.completions(subject, quest.id());
        Quest.Repeat.Reset reset = repeat.reset();
        int periodCount = 1;
        if (reset != null && RepeatPeriod.samePeriod(reset, prior.lastCompletionMs(), nowMs)) {
            periodCount = prior.periodCount() + 1;
        } else if (reset == null) {
            periodCount = 0;
        }
        int total = prior.totalCount() == Integer.MAX_VALUE
                ? Integer.MAX_VALUE : prior.totalCount() + 1;
        store.setCompletions(subject, quest.id(),
                new QuestProgressStore.CompletionRecord(nowMs, periodCount, total));
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
        store.clearQuest(subject, questId);
        store.markDirty(subject);
        Quest quest = quests.get(questId);
        fireAbandoned(questId, subject, quest != null ? quest.tags() : List.of());
        return true;
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
            store.clearQuest(subject, questId);
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
            ObjectiveProgressState state = progress.computeIfAbsent(objective.id(),
                    key -> new ObjectiveProgressState(0, objective.amountAsInt()));
            if (state.isCompleted()) {
                continue;
            }
            int before = state.current();
            boolean justCompleted = state.applyValue(statProbe.valueFor(subject, objective));
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
        if (!pins.containsKey(questId) && pins.size() >= maxTracked) {
            return false;
        }
        store.setTrackedPin(subject, questId, now());
        return true;
    }

    /** Unpin a quest. Returns true when a pin was actually there. */
    public boolean untrack(@Nonnull Subject subject, @Nonnull String questId) {
        return store.clearTrackedPin(subject, questId);
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
            }
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

    private void saveProgress(@Nonnull Subject subject, @Nonnull String questId,
                              @Nonnull Map<String, ObjectiveProgressState> progress) {
        store.putProgressPayload(subject, questId, QuestProgressPayload.serialize(progress));
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
    }

    private void fireCompleted(@Nonnull Quest quest, @Nonnull Subject subject, boolean parked) {
        if (nativeEvents) {
            QuestEvents.fireCompleted(quest.id(), subject.id(), parked, quest.tags());
        }
    }

    private void fireClaimed(@Nonnull Quest quest, @Nonnull Subject subject,
                             @Nonnull RewardGrants.GrantOutcome outcome) {
        if (nativeEvents) {
            QuestEvents.fireClaimed(quest.id(), subject.id(), outcome.granted(), outcome.queued(),
                    outcome.failed(), quest.tags());
        }
    }

    private void fireAbandoned(@Nonnull String questId, @Nonnull Subject subject,
                               @Nonnull List<String> tags) {
        if (nativeEvents) {
            QuestEvents.fireAbandoned(questId, subject.id(), tags);
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
        private MatchFlavor matchFlavor = MatchFlavor.STRICT;
        private QuestPossessionProbe possession = QuestPossessionProbe.NONE;
        private QuestInventoryConsumer inventory = QuestInventoryConsumer.NONE;
        private QuestGates gates = QuestGates.OPEN;
        private ProgressDispatchTap tap = ProgressDispatchTap.NONE;
        @Nullable private FactorRegistry factors;
        @Nullable private Function<Subject, FactorContext> factorContext;
        private QuestI18n i18n = QuestI18n.NONE;
        @Nullable private BiConsumer<Subject, String> rewardRetryQueue;
        private Consumer<String> warn = DEFAULT_WARN;
        private LongSupplier clock = System::currentTimeMillis;
        private int maxTracked = 5;
        private int maxActive;
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

        /** Which matching dialect to run. Defaults to {@link MatchFlavor#STRICT}. */
        @Nonnull
        public Builder matchFlavor(@Nonnull MatchFlavor matchFlavor) {
            this.matchFlavor = matchFlavor;
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
            this.maxActive = Math.max(0, maxActive);
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
