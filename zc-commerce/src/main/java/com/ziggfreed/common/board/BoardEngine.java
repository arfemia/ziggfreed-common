package com.ziggfreed.common.board;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.commerce.CommerceStore;
import com.ziggfreed.common.commerce.CommerceStores;
import com.ziggfreed.common.cost.Cost;
import com.ziggfreed.common.cost.CostEngine;
import com.ziggfreed.common.progress.gate.GateEvaluator;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.rotation.PoolSeed;
import com.ziggfreed.common.rotation.PoolSlot;
import com.ziggfreed.common.rotation.RerollSpec;
import com.ziggfreed.common.rotation.RotationSpec;
import com.ziggfreed.common.rotation.SelectionStrategies;
import com.ziggfreed.common.rotation.SelectionStrategy;
import com.ziggfreed.common.rotation.SlotRerollEngine;
import com.ziggfreed.common.rotation.WeightedSlotDraw;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * A board is a rotating VIEW over the bounty pool.
 *
 * <p>Which contracts are up right now is the pure function
 * {@code (boardId, rotation period, selection, slot filters, seed)} over whichever bounties name
 * that board. Nothing about it is stored: every player computes the same board, a restart changes
 * nothing, and there is no schedule anywhere to drift.
 *
 * <p><b>What the engine owns, so no surface can lose it:</b>
 * <ul>
 *   <li><b>The period lock.</b> A contract completed inside the current rotation period stays spent
 *       until the board turns over, read off the quest engine's own completion record rather than
 *       any bookkeeping of ours.</li>
 *   <li><b>The lapse re-arm.</b> A contract completed in a PAST period is put back within reach
 *       when the board is next looked at, so a rotation genuinely rotates.</li>
 *   <li><b>The accept site.</b> Taking a contract threads the BOARD id, so the quest engine binds
 *       the hand-in to that board with no author writing anything.</li>
 *   <li><b>The pre-charge reroll probe.</b> Whether a reroll can produce anything different is
 *       decided BEFORE a price is drained, so a player is never charged for a guaranteed no-op.</li>
 * </ul>
 *
 * <p><b>Every gate is the shared one.</b> Board access and per-difficulty accept gates are ordinary
 * {@code Requires} blocks answered by the same {@link GateEvaluator} a quest accept uses, so a lock
 * on a board means exactly what a lock on a quest means, and its refusal is worded once.
 *
 * <p>The engine holds no clock. Every entry point takes {@code nowMs}.
 */
public final class BoardEngine {

    /** The board is switched off. */
    public static final String REASON_DISABLED = "disabled";

    /** That bounty is not on the board right now. */
    public static final String REASON_NOT_ON_BOARD = "not_on_board";

    /** Already taken, and still being carried. */
    public static final String REASON_ALREADY_CARRIED = "already_carried";

    /** Already completed this rotation period; it comes back when the board turns over. */
    public static final String REASON_SPENT_THIS_PERIOD = "spent_this_period";

    /** The quest engine refused the accept for its own reasons. */
    public static final String REASON_REFUSED = "refused";

    /** The board offers no reroll at all. */
    public static final String REASON_NO_REROLL = "no_reroll";

    /** Every reroll this period has been spent. */
    public static final String REASON_REROLL_CAP = "reroll:cap";

    /** There is nothing different to swap in, so nothing was charged. */
    public static final String REASON_NO_ALTERNATIVE = "reroll:no_alternative";

    /** The reroll price could not be paid. */
    public static final String REASON_REROLL_CANNOT_PAY = "reroll:cannot_pay";

    /** Whether something may happen, and what stopped it if not. */
    public record BoardCheck(boolean ok, @Nullable String reason) {

        /** Nothing is in the way. */
        public static final BoardCheck OK = new BoardCheck(true, null);

        /** Refused, naming what refused it. */
        @Nonnull
        public static BoardCheck refused(@Nonnull String reason) {
            return new BoardCheck(false, reason);
        }
    }

    /** What a reroll did: the position it changed, what left it, and what took its place. */
    public record RerollResult(boolean ok, @Nullable String reason, int position,
            @Nullable String replacedId, @Nullable String newId) {

        /** Refused, naming what refused it. Nothing was charged. */
        @Nonnull
        public static RerollResult refused(@Nonnull String reason, int position) {
            return new RerollResult(false, reason, position, null, null);
        }
    }

    private final BoardQuests quests;
    private final GateEvaluator gates;
    @Nullable private final CostEngine costs;
    private final Supplier<CommerceStore> store;
    private final Consumer<String> warn;

    private BoardEngine(@Nonnull Builder b) {
        this.quests = b.quests;
        this.gates = b.gates;
        this.costs = b.costs;
        this.store = b.store;
        this.warn = b.warn;
    }

    /** The quest lifecycle every bounty on every board runs through. */
    @Nonnull
    public BoardQuests quests() {
        return quests;
    }

    // ==================== The active set ====================

    /**
     * Which bounties {@code board} is showing at {@code nowMs}, the same for every player.
     *
     * <p>Pure: the pool is filtered to the board's members, drawn by the board's registered
     * selection strategy against a seed folded from the board id and the period, and answered in
     * slot order. No state is read or written.
     */
    @Nonnull
    public List<BountyRef> activeSet(@Nonnull BoardSpec board, @Nonnull Collection<BountyRef> pool,
            long nowMs) {
        return drawFor(board, pool, nowMs).items();
    }

    /**
     * {@link #activeSet} with the per-position slot that produced each entry, for a caller about to
     * reroll one of them.
     */
    @Nonnull
    public WeightedSlotDraw.DrawResult<BountyRef> drawFor(@Nonnull BoardSpec board,
            @Nonnull Collection<BountyRef> pool, long nowMs) {
        SelectionStrategy strategy = SelectionStrategies.forSpec(board.selection());
        if (strategy == null) {
            warn.accept("[board] '" + board.boardId() + "' asks for selection type '"
                    + board.selection().type() + "', which nothing registered, so it shows nothing");
            return WeightedSlotDraw.DrawResult.empty();
        }
        List<BountyRef> candidates = membersOf(board, pool);
        long period = board.rotation().periodIndex(nowMs);
        long seed = PoolSeed.mix(board.boardId(), period, 0);
        return strategy.draw(candidates, board.slots(), BountyRef::bountyId,
                ref -> ref.weightOn(board.boardId()), matcherFor(board), seed, board.defaultCount());
    }

    /**
     * {@link #activeSet} with this subject's own re-rolled positions laid over it, which is what a
     * board actually shows THEM.
     */
    @Nonnull
    public List<BountyRef> activeSetFor(@Nonnull Subject subject, @Nonnull BoardSpec board,
            @Nonnull Collection<BountyRef> pool, long nowMs) {
        WeightedSlotDraw.DrawResult<BountyRef> base = drawFor(board, pool, nowMs);
        Map<Integer, String> overrides = store.get()
                .rerollOverrides(subject, board.boardId(), board.rotation().periodIndex(nowMs));
        if (overrides.isEmpty()) {
            return base.items();
        }
        List<BountyRef> members = membersOf(board, pool);
        return SlotRerollEngine.applyOverrides(base, overrides,
                id -> findById(members, id), matcherFor(board));
    }

    /** Every bounty in {@code pool} that names this board and is not switched off. */
    @Nonnull
    public List<BountyRef> membersOf(@Nonnull BoardSpec board, @Nonnull Collection<BountyRef> pool) {
        List<BountyRef> members = new ArrayList<>();
        for (BountyRef ref : pool) {
            if (ref != null && ref.enabled() && ref.isOn(board.boardId())) {
                members.add(ref);
            }
        }
        return members;
    }

    // ==================== The period lock ====================

    /**
     * Has this subject already completed {@code bountyId} inside the board's CURRENT rotation
     * period? A contract completed this period is spent until the board turns over, whatever the
     * quest's own cooldown says.
     */
    public boolean completedThisPeriod(@Nonnull Subject subject, @Nonnull BoardSpec board,
            @Nonnull String bountyId, long nowMs) {
        long last = quests.lastCompletionMs(subject, bountyId);
        return last > 0L && board.rotation().samePeriod(last, nowMs);
    }

    /**
     * Put every bounty on this board that was completed in a PAST period back within reach, and
     * answer how many were re-armed.
     *
     * <p>Called wherever a board is about to be looked at, which is what makes a rotation genuinely
     * rotate without a timer anywhere. A bounty the subject is carrying is left alone, so nothing
     * in progress is ever discarded.
     */
    public int reArmLapsed(@Nonnull Subject subject, @Nonnull BoardSpec board,
            @Nonnull Collection<BountyRef> pool, long nowMs) {
        int reArmed = 0;
        for (BountyRef ref : membersOf(board, pool)) {
            String bountyId = ref.bountyId();
            long last = quests.lastCompletionMs(subject, bountyId);
            if (last <= 0L || board.rotation().samePeriod(last, nowMs)) {
                continue;
            }
            if (quests.isCarried(subject, bountyId)) {
                continue;
            }
            quests.reArm(subject, bountyId);
            reArmed++;
        }
        return reArmed;
    }

    // ==================== Accept ====================

    /**
     * May this subject take {@code bounty} off {@code board} right now? Board access, the
     * per-difficulty accept gate, the period lock, and whether they are already carrying it - asked
     * in that order, without taking anything.
     */
    @Nonnull
    public BoardCheck canAccept(@Nonnull Subject subject, @Nonnull BoardSpec board,
            @Nonnull BountyRef bounty, long nowMs) {
        if (!board.enabled()) {
            return BoardCheck.refused(REASON_DISABLED);
        }
        if (!bounty.enabled() || !bounty.isOn(board.boardId())) {
            return BoardCheck.refused(REASON_NOT_ON_BOARD);
        }
        String boardFailure = gates.firstFailure(subject, board.requires());
        if (boardFailure != null) {
            return BoardCheck.refused(boardFailure);
        }
        String gradeFailure = gates.firstFailure(subject,
                acceptGateFor(board, bounty.difficultyOn(board.boardId())));
        if (gradeFailure != null) {
            return BoardCheck.refused(gradeFailure);
        }
        if (quests.isCarried(subject, bounty.bountyId())) {
            return BoardCheck.refused(REASON_ALREADY_CARRIED);
        }
        if (completedThisPeriod(subject, board, bounty.bountyId(), nowMs)) {
            return BoardCheck.refused(REASON_SPENT_THIS_PERIOD);
        }
        return BoardCheck.OK;
    }

    /**
     * Take {@code bounty} off {@code board}, recording the BOARD as where it was taken, so the
     * quest engine binds the hand-in to it.
     */
    @Nonnull
    public BoardCheck accept(@Nonnull Subject subject, @Nonnull BoardSpec board,
            @Nonnull BountyRef bounty, long nowMs) {
        BoardCheck check = canAccept(subject, board, bounty, nowMs);
        if (!check.ok()) {
            return check;
        }
        if (!quests.accept(subject, bounty.bountyId(), board.boardId())) {
            return BoardCheck.refused(REASON_REFUSED);
        }
        return BoardCheck.OK;
    }

    /**
     * The requirement block guarding contracts of {@code difficulty} on this board, matched
     * case-insensitively, or null when that grade is open to everyone.
     */
    @Nullable
    public GateSpec acceptGateFor(@Nonnull BoardSpec board, @Nullable String difficulty) {
        if (difficulty == null || difficulty.isBlank()) {
            return null;
        }
        Map<String, GateSpec> gatesByGrade = board.acceptRequires();
        GateSpec exact = gatesByGrade.get(difficulty);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, GateSpec> entry : gatesByGrade.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(difficulty.trim())) {
                return entry.getValue();
            }
        }
        return null;
    }

    // ==================== Reroll ====================

    /**
     * Could a reroll of {@code position} produce anything at all, and is one still allowed?
     *
     * <p><b>Asked BEFORE any price is drained</b>, which is what stops a player paying for a reroll
     * that visibly changes nothing. It does not check whether they can afford it; that is the
     * reroll's own next step.
     */
    @Nonnull
    public BoardCheck canReroll(@Nonnull Subject subject, @Nonnull BoardSpec board,
            @Nonnull Collection<BountyRef> pool, int position, long nowMs) {
        RerollSpec spec = board.reroll();
        if (spec == null) {
            return BoardCheck.refused(REASON_NO_REROLL);
        }
        CommerceStore state = store.get();
        long period = board.rotation().periodIndex(nowMs);
        if (!state.recordsRerolls()) {
            warn.accept("[board] '" + board.boardId() + "' offers rerolls but this server's commerce "
                    + "store keeps none, so a paid reroll would not survive a relog");
        }
        if (!spec.allows(state.rerollsSpent(subject, board.boardId(), period))) {
            return BoardCheck.refused(REASON_REROLL_CAP);
        }
        return replacementFor(subject, board, pool, position, nowMs) == null
                ? BoardCheck.refused(REASON_NO_ALTERNATIVE)
                : BoardCheck.OK;
    }

    /**
     * Swap what sits at {@code position} for something different, charging the board's reroll price.
     *
     * <p>The order is the point: probe for an alternative, drain, commit, and give the price back if
     * the commit lost a race with the cap. Nothing is charged for a reroll that could not have
     * happened.
     */
    @Nonnull
    public RerollResult reroll(@Nonnull Subject subject, @Nonnull BoardSpec board,
            @Nonnull Collection<BountyRef> pool, int position, long nowMs) {
        RerollSpec spec = board.reroll();
        if (spec == null) {
            return RerollResult.refused(REASON_NO_REROLL, position);
        }
        BoardCheck probe = canReroll(subject, board, pool, position, nowMs);
        if (!probe.ok()) {
            return RerollResult.refused(probe.reason() == null ? REASON_NO_ALTERNATIVE : probe.reason(),
                    position);
        }

        BountyRef replacement = replacementFor(subject, board, pool, position, nowMs);
        if (replacement == null) {
            return RerollResult.refused(REASON_NO_ALTERNATIVE, position);
        }
        List<BountyRef> shown = activeSetFor(subject, board, pool, nowMs);
        String replacedId = (position >= 0 && position < shown.size())
                ? shown.get(position).bountyId() : null;

        Cost price = spec.cost();
        CostEngine.Receipt receipt = CostEngine.Receipt.FREE;
        if (!price.isFree()) {
            if (costs == null) {
                warn.accept("[board] '" + board.boardId() + "' charges for rerolls but no price authority "
                        + "was wired, so the reroll was refused rather than given away");
                return RerollResult.refused(REASON_REROLL_CANNOT_PAY, position);
            }
            receipt = costs.drain(subject, price);
            if (!receipt.ok()) {
                return RerollResult.refused(REASON_REROLL_CANNOT_PAY, position);
            }
        }

        long period = board.rotation().periodIndex(nowMs);
        boolean committed = store.get().commitReroll(subject, board.boardId(), period,
                spec.maxPerPeriod(), position, replacedId, replacement.bountyId());
        if (!committed) {
            if (costs != null) {
                costs.refund(subject, receipt);
            }
            return RerollResult.refused(REASON_REROLL_CAP, position);
        }
        return new RerollResult(true, null, position, replacedId, replacement.bountyId());
    }

    /**
     * What a reroll of {@code position} would put there, or null when nothing different qualifies.
     *
     * <p>Excludes everything currently on show AND everything that has already sat at this position
     * this period, so a reroll can never hand back a contract the player has already turned down.
     */
    @Nullable
    public BountyRef replacementFor(@Nonnull Subject subject, @Nonnull BoardSpec board,
            @Nonnull Collection<BountyRef> pool, int position, long nowMs) {
        WeightedSlotDraw.DrawResult<BountyRef> base = drawFor(board, pool, nowMs);
        if (position < 0 || position >= base.size()) {
            return null;
        }
        List<BountyRef> shown = activeSetFor(subject, board, pool, nowMs);
        CommerceStore state = store.get();
        long period = board.rotation().periodIndex(nowMs);
        Set<String> seen = state.rerollSeenAt(subject, board.boardId(), period, position);
        Set<String> exclude = SlotRerollEngine.excludeAll(shown, BountyRef::bountyId, seen);

        int nextCount = state.rerollNextCount(subject, board.boardId(), period, position);
        long seed = PoolSeed.mix(board.boardId(), period, position, nextCount);
        PoolSlot slot = base.slotAt(position);
        return WeightedSlotDraw.drawReplacement(membersOf(board, pool), slot, BountyRef::bountyId,
                ref -> ref.weightOn(board.boardId()), matcherFor(board), exclude, seed);
    }

    // ==================== Internals ====================

    /** A slot accepts a bounty when the bounty's grade ON THIS BOARD is the grade the slot asks for. */
    @Nonnull
    private static WeightedSlotDraw.SlotMatcher<BountyRef> matcherFor(@Nonnull BoardSpec board) {
        return (ref, slot) -> slot.accepts(ref.difficultyOn(board.boardId()), null);
    }

    @Nullable
    private static BountyRef findById(@Nonnull List<BountyRef> members, @Nullable String bountyId) {
        if (bountyId == null) {
            return null;
        }
        for (BountyRef ref : members) {
            if (ref.bountyId().equalsIgnoreCase(bountyId)) {
                return ref;
            }
        }
        return null;
    }

    @Nonnull
    public static Builder builder(@Nonnull BoardQuests quests, @Nonnull GateEvaluator gates) {
        return new Builder(quests, gates);
    }

    /** Assembles a {@link BoardEngine}. */
    public static final class Builder {

        private final BoardQuests quests;
        private final GateEvaluator gates;
        @Nullable private CostEngine costs;
        private Supplier<CommerceStore> store = CommerceStores::get;
        private Consumer<String> warn = SafeLog::warn;

        private Builder(@Nonnull BoardQuests quests, @Nonnull GateEvaluator gates) {
            this.quests = quests;
            this.gates = gates;
        }

        /**
         * Who charges a reroll. Unset REFUSES a paid reroll rather than giving it away, so a board
         * on a server that wired no economy stops offering rerolls instead of offering free ones.
         */
        @Nonnull
        public Builder costs(@Nullable CostEngine costs) {
            this.costs = costs;
            return this;
        }

        /** Where reroll state lives. Defaults to whatever is installed at call time. */
        @Nonnull
        public Builder store(@Nonnull Supplier<CommerceStore> store) {
            this.store = store;
            return this;
        }

        /** A fixed store, for a test that wants to hold the instance it drives. */
        @Nonnull
        public Builder store(@Nonnull CommerceStore store) {
            this.store = () -> store;
            return this;
        }

        /** Where an unregistered selection type and an unwired price authority are reported. */
        @Nonnull
        public Builder warn(@Nonnull Consumer<String> warn) {
            this.warn = warn;
            return this;
        }

        @Nonnull
        public BoardEngine build() {
            return new BoardEngine(this);
        }
    }
}
