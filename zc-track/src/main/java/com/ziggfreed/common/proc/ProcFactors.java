package com.ziggfreed.common.proc;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorContributions;

/**
 * The counters' reading, contributed once at setup so every factor vocabulary resolves it:
 * {@value #COUNTER_STACKS} (Param a counter's id), the stacks the asking player holds of it right now (of a
 * {@code PerTarget} counter, the stacks about the context's target), 0 for none, null with no player. It moves
 * outside every moment (an add, a decay), so no gate keeps a verdict over it.
 */
final class ProcFactors {

    /** {@code ziggfreedcommon:counter_stacks}: the asking player's stacks of the counter Param names. */
    public static final String COUNTER_STACKS = "ziggfreedcommon:counter_stacks";

    private ProcFactors() {
    }

    /** Claim the reading process-wide. */
    public static void contribute() {
        FactorContributions.register(COUNTER_STACKS, Procs.OWNER, ProcFactors::stacks);
    }

    @Nullable
    static Double stacks(@Nonnull FactorContext context) {
        String counter = context.param();
        if (counter == null || counter.isBlank() || context.subject() == null) {
            return null;
        }
        return Counters.stacks(context.subject(), counter, context.target());
    }
}
