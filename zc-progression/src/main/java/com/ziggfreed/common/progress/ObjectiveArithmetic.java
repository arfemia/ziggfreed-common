package com.ziggfreed.common.progress;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The ONE compare every lifecycle engine runs when a moment reaches an objective: which arithmetic
 * the objective's registered {@link ObjectiveKind} asks for, applied to its
 * {@link ObjectiveProgressState}.
 *
 * <p>Three readings, decided by two independent knobs on the kind:
 * <ul>
 *   <li>an ACCUMULATING kind adds the fired amount ({@link ObjectiveProgressState#advance});</li>
 *   <li>a VALUE-BASED kind raises a high-water mark to the fired value
 *   ({@link ObjectiveProgressState#applyValue});</li>
 *   <li>a value-based kind that is also {@code atMost} treats the authored amount as a CEILING: the
 *   objective is met the first time a fired value comes in at or under it, and a value over it
 *   moves nothing. Its recorded progress is not the value at all but a binary met-or-not
 *   ({@code 0/1}), because a ceiling has no natural "how far along" and the state's wire form is
 *   a count, so a zero ceiling ("no deaths") is not born completed the way a zero count would be.</li>
 * </ul>
 *
 * <p>Both engines mint their states through {@link #fresh} and {@link #stored} and apply through
 * {@link #apply} (a produced moment) or {@link #applyStanding} (a value the engine read for itself,
 * which is always a standing value however the kind accumulates), so the third reading exists in
 * exactly one place; a kind the engine does not know reads as accumulating, which is what a bare
 * registration would have said.
 */
public final class ObjectiveArithmetic {

    /** What a ceiling objective's progress counts to: met, or not yet. */
    static final int CEILING_REQUIRED = 1;

    private ObjectiveArithmetic() {
    }

    /** Does this kind read the authored amount as a ceiling on a fired value? */
    public static boolean isCeiling(@Nullable ObjectiveKind kind) {
        return kind != null && kind.valueBased() && kind.atMost();
    }

    /** The count an objective of this kind is complete at: the authored amount, or 1 for a ceiling. */
    public static int requiredFor(@Nullable ObjectiveKind kind, @Nonnull ObjectiveDef objective) {
        return isCeiling(kind) ? CEILING_REQUIRED : objective.amountAsInt();
    }

    /** A state with nothing recorded yet, sized for {@code objective} under {@code kind}. */
    @Nonnull
    public static ObjectiveProgressState fresh(@Nullable ObjectiveKind kind, @Nonnull ObjectiveDef objective) {
        return new ObjectiveProgressState(0, requiredFor(kind, objective));
    }

    /** A state rebuilt from a stored count, sized for {@code objective} under {@code kind}. */
    @Nonnull
    public static ObjectiveProgressState stored(@Nullable ObjectiveKind kind, @Nonnull ObjectiveDef objective,
            long current) {
        int clamped = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, current));
        return new ObjectiveProgressState(clamped, requiredFor(kind, objective));
    }

    /**
     * Apply one fired {@code amount} to {@code state} the way {@code kind} counts, and say whether
     * THIS call finished the objective. A finished state is left alone whatever arrives.
     */
    public static boolean apply(@Nullable ObjectiveKind kind, @Nonnull ObjectiveDef objective,
            @Nonnull ObjectiveProgressState state, long amount) {
        if (state.isCompleted()) {
            return false;
        }
        if (isCeiling(kind)) {
            return meetCeiling(objective, state, amount);
        }
        if (kind != null && kind.valueBased()) {
            return state.applyValue(amount);
        }
        return state.advance(amount);
    }

    /**
     * Apply a STANDING {@code value} the engine read for itself (a stat channel re-read, what a
     * player already holds at accept) and say whether THIS call finished the objective. A standing
     * value is a high-water mark whatever the kind's own arithmetic, because it is the player's
     * current reading and never an increment; under a ceiling it is compared the same way a fired
     * value is.
     */
    public static boolean applyStanding(@Nullable ObjectiveKind kind, @Nonnull ObjectiveDef objective,
            @Nonnull ObjectiveProgressState state, long value) {
        if (state.isCompleted()) {
            return false;
        }
        if (isCeiling(kind)) {
            return meetCeiling(objective, state, value);
        }
        return state.applyValue(value);
    }

    /** The ceiling compare: at or under the authored amount is met, over it moves nothing. */
    private static boolean meetCeiling(@Nonnull ObjectiveDef objective, @Nonnull ObjectiveProgressState state,
            long value) {
        if (value > objective.amount()) {
            return false;
        }
        return state.advance(Math.max(1L, (long) state.required() - state.current()));
    }
}
