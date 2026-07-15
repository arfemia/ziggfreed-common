package com.ziggfreed.common.cast.step;

import javax.annotation.Nonnull;

/**
 * The generic ordered step-dispatch walk lifted from a consumer's cast / ability engine: iterate a
 * cast context's steps IN ORDER, look each step's handler up by key, short-circuit on the first
 * non-success result, and defer the missing-handler behavior to the consumer.
 *
 * <p>Common owns only this dispatch MECHANISM; the step vocabulary ({@code S}), the discriminator
 * ({@code K}), the context ({@code C}), and the result ({@code R}) are all the consumer's own types,
 * wired in through a {@link StepSemantics}. A consumer builds a kernel over its production
 * {@link StepRegistry} + a {@code StepSemantics} adapting its existing spec / step / result methods,
 * then calls {@link #run(Object)} in place of its hand-written loop.
 *
 * <p><b>Byte-parity.</b> The walk is exactly:
 * <pre>
 *   for (S step : semantics.stepsOf(ctx)) {
 *       K key = semantics.keyOf(step);
 *       StepHandler&lt;C,S,R&gt; handler = registry.get(key);   // get() returns null for a null key
 *       if (handler == null) return semantics.onMissingHandler(ctx, step, key);
 *       R result = handler.execute(ctx, step);
 *       if (!semantics.isSuccess(result)) return result;
 *   }
 *   return semantics.successResult(ctx);
 * </pre>
 * An empty step chain returns {@code successResult}. The missing-handler path returns whatever the
 * consumer's {@code onMissingHandler} produces (the consumer logs there, with its own id), so a
 * consumer that logged {@code severe} + returned an error result reproduces that result byte-for-byte.
 * The kernel does NOT catch a handler throw - a consumer whose loop never wrapped the handler call
 * keeps identical throw behavior.
 *
 * @param <C> the cast context type
 * @param <S> the step type
 * @param <K> the step key type
 * @param <R> the result type
 */
public final class CastKernel<C, S, K, R> {

    private final StepRegistry<K, C, S, R> registry;
    private final StepSemantics<C, S, K, R> semantics;

    /**
     * @param registry  the handler registry (a consumer's production instance)
     * @param semantics the consumer-supplied contract adapting its domain types to the walk
     */
    public CastKernel(@Nonnull StepRegistry<K, C, S, R> registry,
                      @Nonnull StepSemantics<C, S, K, R> semantics) {
        this.registry = registry;
        this.semantics = semantics;
    }

    /**
     * Walk {@code ctx}'s steps in order, dispatching each through the registry and short-circuiting
     * on the first non-success result. Returns the consumer's success result when every step passes
     * (including an empty chain), the consumer's missing-handler result when a step's key has no
     * handler, or the first non-success step result.
     */
    @Nonnull
    public R run(@Nonnull C ctx) {
        for (S step : semantics.stepsOf(ctx)) {
            K key = semantics.keyOf(step);
            StepHandler<C, S, R> handler = registry.get(key);
            if (handler == null) {
                return semantics.onMissingHandler(ctx, step, key);
            }
            R result = handler.execute(ctx, step);
            if (!semantics.isSuccess(result)) {
                return result;
            }
        }
        return semantics.successResult(ctx);
    }
}
