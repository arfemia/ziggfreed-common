package com.ziggfreed.common.progress.gate;

import javax.annotation.Nullable;

/**
 * What {@link GateEvaluator#check} answered: whether the block passed and, when it did not, the reason token for
 * the first requirement that shut it (the token {@link GateEvaluator#firstFailure} would name). A surface that
 * explains the refusal asks {@link GateEvaluator#refusals} for the whole list, only when it is going to paint it.
 *
 * @param firstFailure the first refusal's token, null exactly when the block passed
 */
public record GateVerdict(@Nullable String firstFailure) {

    /** The block passed. */
    public static final GateVerdict PASS = new GateVerdict(null);

    /** The verdict for a first failure token, {@link #PASS} for null. */
    public static GateVerdict of(@Nullable String firstFailure) {
        return firstFailure == null ? PASS : new GateVerdict(firstFailure);
    }

    /** Did the block pass? */
    public boolean passed() {
        return firstFailure == null;
    }
}
