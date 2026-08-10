package com.ziggfreed.common.cast.step;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The tiny consumer-supplied contract that parameterizes a {@link CastKernel} walk over a
 * consumer's OWN domain types, so common's kernel reproduces a consumer's existing step-engine
 * loop byte-identically without knowing any of the consumer's types.
 *
 * <p><b>Why this shape (a small multi-method bundle) and not a per-type interface trio.</b> The
 * alternative - making the consumer's step type implement a {@code CastStep}, its result a
 * {@code StepOutcome}, its context a {@code StepChain} - would force {@code implements} clauses
 * onto the consumer's existing domain classes. This bundle instead lets the consumer wire its
 * EXISTING methods (via method references) into ONE small implementation, so its context / spec /
 * step / result types stay structurally untouched and the delegation is a pure adapter. That
 * keeps a consumer's step vocabulary free to evolve (nothing about it is frozen into common) and
 * makes the kernel a drop-in replacement for a hand-written dispatch loop.
 *
 * <p>The five methods map one-to-one onto the classic loop {@code for (step : stepsOf(ctx)) &#123;
 * key = keyOf(step); handler = registry.get(key); if (handler == null) return onMissingHandler(...);
 * result = handler.execute(ctx, step); if (!isSuccess(result)) return result; &#125; return
 * successResult(ctx);}. The missing-handler hook stays consumer-side so the consumer keeps its own
 * logging (with its own ids) and its own error-result production - common never logs a domain
 * message here.
 *
 * @param <C> the cast context type
 * @param <S> the step type
 * @param <K> the step key type
 * @param <R> the result type
 */
public interface StepSemantics<C, S, K, R> {

    /** The ordered steps of {@code ctx} to walk (an empty iterable succeeds). */
    @Nonnull
    Iterable<S> stepsOf(@Nonnull C ctx);

    /** The registry key for {@code step}, or {@code null} when the step has no key (treated as missing-handler). */
    @Nullable
    K keyOf(@Nonnull S step);

    /** Whether {@code result} lets the walk continue; a non-success result short-circuits and is returned as-is. */
    boolean isSuccess(@Nonnull R result);

    /** The result for a completed walk (an empty chain, or every step succeeding). */
    @Nonnull
    R successResult(@Nonnull C ctx);

    /**
     * Produce the result for a step whose key has no registered handler. This is the consumer's
     * hook to log (with its own id) AND build its own error result; common performs no logging and
     * fabricates no result here. Called at most once per walk (it short-circuits).
     *
     * @param ctx  the cast context
     * @param step the step whose handler was missing
     * @param key  the resolved key (may be {@code null} if {@link #keyOf} returned null)
     */
    @Nonnull
    R onMissingHandler(@Nonnull C ctx, @Nonnull S step, @Nullable K key);

    /**
     * Whether {@code result} signals a WALK SUSPENSION for {@link CastKernel#runResumable} (default
     * {@code false}): a {@code true} return produces a {@code Walk.Suspended(atIndex, result)} instead
     * of the walk continuing or failing. A consumer that never suspends (the byte-parity default for
     * {@link CastKernel#run}) inherits this and only ever sees {@code Completed}/{@code Failed} walks.
     *
     * <p><b>Resume contract (binding) - read before overriding.</b> When this returns {@code true} for
     * a step's result, {@code atIndex} in the returned {@code Suspended} is that step's OWN index, and
     * resuming re-enters that SAME step (it runs again from the top of its {@link StepHandler}) - see
     * {@link CastKernel#runResumable}'s javadoc for the full contract. A suspending ("Wait"-style)
     * step must derive its suspend/continue decision from state the consumer holds across the
     * suspension (a deadline written once, read - never re-derived - on every re-entry); otherwise a
     * naive re-entry that recomputes "not yet due" from scratch every time never converges and
     * re-suspends forever.
     */
    default boolean isSuspend(@Nonnull R result) {
        return false;
    }

    /**
     * The index {@link CastKernel#runResumable} continues the walk at after {@code step} (currently at
     * {@code currentIndex}) returns a success-continuing {@code result} (default
     * {@code currentIndex + 1}, the classic linear advance). A consumer authoring branch/skip
     * semantics (e.g. an {@code OnConditionFail.Goto}-style jump) wires it here instead of adding a
     * dedicated branch step type. The kernel applies no bounds/loop protection beyond the walk's own
     * {@code [0, stepCount)} termination - a {@code nextIndex} that never converges toward the end of
     * the chain is a content-authoring bug for the consumer's own validator to catch.
     */
    default int nextIndex(@Nonnull C ctx, @Nonnull S step, int currentIndex, @Nonnull R result) {
        return currentIndex + 1;
    }
}
