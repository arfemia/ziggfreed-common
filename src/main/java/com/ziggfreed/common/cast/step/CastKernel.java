package com.ziggfreed.common.cast.step;

import java.util.ArrayList;
import java.util.List;

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
 * then calls {@link #run(Object)} (a one-shot walk, e.g. an MMO ability cast) or
 * {@link #runResumable(Object, int)} (a walk that can pause ACROSS FRAMES, e.g. a station session
 * with a multi-tick {@code Wait} step) in place of its hand-written loop.
 *
 * <p><b>Byte-parity of {@link #run}.</b> {@code run(ctx)} is defined as {@code runResumable(ctx, 0)}
 * unwrapped to a bare {@code R} (see {@link #run} for the unwrap rule), and reproduces the original
 * hand-written loop exactly for any consumer that does not override {@link StepSemantics#isSuspend}
 * or {@link StepSemantics#nextIndex} (both default to the pre-reshape behavior - see their javadoc):
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
 *
 * <p><b>No-catch contract (binding, applies to BOTH {@link #run} and {@link #runResumable}).</b> The
 * kernel does NOT catch a handler throw - a consumer whose loop never wrapped the handler call keeps
 * identical throw behavior, and a throw unwinds straight out of whichever entry point was called.
 * This is deliberate even for a consumer that dispatches inside a SHARED per-tick system (e.g. a
 * per-world frame drain) where one throwing step must not be allowed to crash something wider than
 * its own walk: the fix is NOT a kernel-side try/catch (that would need to invent a domain
 * {@code R} it has no way to construct, and would swallow a throw the ability caller currently lets
 * propagate). Instead, fault-isolation is a SEAM at the {@link StepHandler} boundary - a consumer
 * wraps each handler it REGISTERS (not the kernel's dispatch loop) in its own guard that catches a
 * throw and converts it to that consumer's own failure {@code R} + a guarded warn log, mirroring
 * {@link com.ziggfreed.common.cast.ObserverRegistry}'s per-listener guard
 * ("one throwing listener neither throws out of {@code fire} nor blocks the rest"). Once a handler
 * degrades a throw to a failure result instead of propagating it, the kernel's ordinary
 * non-success short-circuit produces {@code Failed(atIndex, thatResult)} with the CORRECT
 * {@code atIndex} for free - no kernel change needed. A per-world-frame consumer (e.g. RpgStations'
 * station step dispatch) registers every handler already wrapped this way; the ability caller, which
 * never wrapped its handlers, is untouched and keeps propagating a throw exactly as before.
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
     * The outcome of a {@link #runResumable} walk: exactly one of a completed walk, a walk that
     * suspended mid-chain and can be resumed, or a walk that failed (a non-success step result, or a
     * missing handler). Sealed so a consumer's {@code switch} over it is exhaustive with no default
     * branch.
     *
     * @param <R> the result type
     */
    public sealed interface Walk<R> {

        /** Every step succeeded (or the chain was empty); {@code result} is {@link StepSemantics#successResult}. */
        record Completed<R>(@Nonnull R result) implements Walk<R> { }

        /**
         * A step signalled suspension ({@link StepSemantics#isSuspend} returned {@code true} for its
         * result). {@code resumeIndex} is that SUSPENDING step's own index (not the next one) - resume
         * by calling {@link #runResumable} again with {@code startIndex = resumeIndex}, which RE-ENTERS
         * that same step. {@code signal} is the step's own result value (carries whatever wait/reason
         * payload the consumer's {@code R} type holds).
         */
        record Suspended<R>(int resumeIndex, @Nonnull R signal) implements Walk<R> { }

        /**
         * A step's result was non-success ({@link StepSemantics#isSuccess} returned {@code false}), or
         * a step's key had no registered handler. {@code atIndex} is that step's index; {@code result}
         * is the failing step's result, or {@link StepSemantics#onMissingHandler}'s result for a
         * missing handler (returned verbatim, exactly as {@link #run}'s pre-reshape contract did).
         */
        record Failed<R>(int atIndex, @Nonnull R result) implements Walk<R> { }
    }

    /**
     * Walk {@code ctx}'s steps in order, dispatching each through the registry and short-circuiting
     * on the first non-success result. Returns the consumer's success result when every step passes
     * (including an empty chain), the consumer's missing-handler result when a step's key has no
     * handler, or the first non-success step result.
     *
     * <p>Implemented as {@code runResumable(ctx, 0)} unwrapped: {@code Completed}/{@code Failed} both
     * unwrap to their carried {@code result} (byte-identical to the pre-reshape loop for a consumer
     * whose {@link StepSemantics} never overrides {@link StepSemantics#isSuspend} - the default always
     * returns {@code false}, so a {@code Suspended} walk never occurs on that path). A consumer whose
     * semantics DOES override {@code isSuspend} but calls this one-shot {@code run} instead of
     * {@link #runResumable} gets the suspending step's raw {@code signal} back (there is no
     * {@code resumeIndex} to hand anywhere) - such a consumer should call {@link #runResumable}
     * directly so it can actually resume.
     */
    @Nonnull
    public R run(@Nonnull C ctx) {
        return switch (runResumable(ctx, 0)) {
            case Walk.Completed<R> completed -> completed.result();
            case Walk.Suspended<R> suspended -> suspended.signal();
            case Walk.Failed<R> failed -> failed.result();
        };
    }

    /**
     * Walk {@code ctx}'s steps starting at {@code startIndex}, dispatching each through the registry,
     * until a step suspends, a step fails (or has no handler), or every remaining step succeeds.
     *
     * <p>{@code startIndex} MUST be in {@code [0, stepCount]}; {@code stepCount} (or anything at/past
     * it) walks zero steps and returns {@code Completed(successResult(ctx))} - "resuming" a chain
     * that already finished is a no-op success, matching the empty-chain case. A negative
     * {@code startIndex} is likewise treated as nothing-left-to-walk (no bounds exception).
     *
     * <p><b>Resume contract (binding).</b> {@code Suspended.resumeIndex} is the index of the step that
     * suspended, and resuming means calling this method again with that same index as
     * {@code startIndex} - the walk RE-ENTERS the suspending step rather than advancing past it. A
     * suspending step (a "Wait"-style step) MUST therefore make its suspend/continue decision from
     * state the CONSUMER holds across frames (e.g. a deadline timestamp written to session state the
     * FIRST time the step runs, then only READ - never re-derived - on every re-entry), so that
     * re-entry is idempotent: the step keeps returning a suspend result until its externally-tracked
     * condition is due, then returns success exactly once. A step that recomputes a fresh "not yet
     * due" window on every call instead of reading the one it already committed can never converge -
     * it re-suspends forever. This method itself is stateless between calls (a fresh {@code ctx} each
     * time is expected, e.g. a per-frame drain with a fresh store/command-buffer accessor); all
     * resume state must live on the consumer's own session object, addressable from {@code ctx}.
     *
     * <p>On a success-continuing result, the next index is {@link StepSemantics#nextIndex} (default
     * {@code currentIndex + 1}, the classic linear advance) - a consumer authoring branch/skip
     * semantics (e.g. an {@code OnConditionFail.Goto}) wires it there. This method applies no loop
     * protection beyond its own {@code [0, stepCount)} termination bound: a {@code nextIndex} that
     * never converges toward the end of the chain is a CONTENT authoring bug for the consumer's own
     * validator to catch, not this method's concern.
     *
     * <p>See the class javadoc for the no-catch / fault-isolation contract, which applies identically
     * to this method.
     */
    @Nonnull
    public Walk<R> runResumable(@Nonnull C ctx, int startIndex) {
        List<S> steps = toList(semantics.stepsOf(ctx));
        int index = startIndex;
        while (index >= 0 && index < steps.size()) {
            S step = steps.get(index);
            K key = semantics.keyOf(step);
            StepHandler<C, S, R> handler = registry.get(key);
            if (handler == null) {
                return new Walk.Failed<>(index, semantics.onMissingHandler(ctx, step, key));
            }
            R result = handler.execute(ctx, step);
            if (semantics.isSuspend(result)) {
                return new Walk.Suspended<>(index, result);
            }
            if (!semantics.isSuccess(result)) {
                return new Walk.Failed<>(index, result);
            }
            index = semantics.nextIndex(ctx, step, index, result);
        }
        return new Walk.Completed<>(semantics.successResult(ctx));
    }

    @Nonnull
    private static <S> List<S> toList(@Nonnull Iterable<S> iterable) {
        List<S> list = new ArrayList<>();
        for (S s : iterable) {
            list.add(s);
        }
        return list;
    }
}
