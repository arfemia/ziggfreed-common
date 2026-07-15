package com.ziggfreed.common.cast.step;

/**
 * Executes ONE step of a cast against a context, returning a result the {@link CastKernel}
 * inspects to decide whether to continue or short-circuit the chain.
 *
 * <p>The generic runtime SAM a consumer's concrete step handlers implement. All three type
 * parameters are the CONSUMER's own domain types - common defines no step vocabulary, no
 * result type, and no context type of its own:
 * <ul>
 *   <li>{@code C} - the cast context (a consumer's per-cast state / caster facet).</li>
 *   <li>{@code S} - one step in the cast's ordered step chain (a consumer's step model).</li>
 *   <li>{@code R} - the per-step result (a consumer's success / failure value).</li>
 * </ul>
 *
 * <p>A handler reads its typed step and applies its effect, returning a success result to
 * continue the chain or a failure result to stop it. Implementations should degrade to a
 * result, never throw - the kernel does not catch a handler throw (byte-parity with a
 * consumer whose engine never wrapped the handler call).
 *
 * @param <C> the cast context type
 * @param <S> the step type
 * @param <R> the result type
 */
@FunctionalInterface
public interface StepHandler<C, S, R> {

    /**
     * Execute {@code step} against {@code ctx}.
     *
     * @param ctx  the cast context
     * @param step the step to execute
     * @return the result the kernel inspects (success continues the chain, failure stops it)
     */
    R execute(C ctx, S step);
}
