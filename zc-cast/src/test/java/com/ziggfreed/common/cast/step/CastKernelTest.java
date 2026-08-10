package com.ziggfreed.common.cast.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

/**
 * Characterization tests for the generic {@link CastKernel} walk. Uses purely test-local domain
 * types (a context holding a step list, a keyed step, a success/failure result) wired through a
 * {@link StepSemantics}, so nothing engine-side is needed. Verifies order, short-circuit, the
 * missing-handler hook, and the empty-chain success case - the byte-parity contract a consumer's
 * hand-written dispatch loop must reproduce.
 *
 * <p>The second half of this file (RPG Stations phase-2 leg A, design section 9.3 / critique M4)
 * covers the {@link CastKernel#runResumable} walk: suspend/resume, {@code nextIndex} branching, the
 * no-catch contract, and the per-handler fault-isolation SEAM - plus an explicit regression proving
 * the "ability-style" (boolean-only, no {@code isSuspend}/{@code nextIndex} override) path still
 * short-circuits identically after the reshape (M4c).
 */
class CastKernelTest {

    /** Test step: just a key. */
    private record Step(@Nullable String key) { }

    /** Test result: success flag + a label so a caller can tell which result came back. */
    private record Result(boolean success, @Nonnull String label) { }

    /** Test context: an ordered step list + a mutable execution trace. */
    private static final class Ctx {
        final List<Step> steps = new ArrayList<>();
        final List<String> trace = new ArrayList<>();
    }

    private static final Result SUCCESS = new Result(true, "SUCCESS");

    /** Semantics wiring the test types to the walk. onMissingHandler records + returns a distinct result. */
    private static final StepSemantics<Ctx, Step, String, Result> SEMANTICS =
            new StepSemantics<>() {
                @Nonnull
                @Override
                public Iterable<Step> stepsOf(@Nonnull Ctx ctx) {
                    return ctx.steps;
                }

                @Nullable
                @Override
                public String keyOf(@Nonnull Step step) {
                    return step.key();
                }

                @Override
                public boolean isSuccess(@Nonnull Result result) {
                    return result.success();
                }

                @Nonnull
                @Override
                public Result successResult(@Nonnull Ctx ctx) {
                    return SUCCESS;
                }

                @Nonnull
                @Override
                public Result onMissingHandler(@Nonnull Ctx ctx, @Nonnull Step step, @Nullable String key) {
                    ctx.trace.add("MISSING:" + key);
                    return new Result(false, "MISSING:" + key);
                }
            };

    /** A handler that records its key into the ctx trace and returns {@code result}. */
    private static StepHandler<Ctx, Step, Result> recordingHandler(String token, Result result) {
        return (ctx, step) -> {
            ctx.trace.add(token);
            return result;
        };
    }

    @Test
    void walksStepsInOrderAndSucceedsWhenAllPass() {
        StepRegistry<String, Ctx, Step, Result> registry = new StepRegistry<>();
        registry.register("A", recordingHandler("A", SUCCESS));
        registry.register("B", recordingHandler("B", SUCCESS));
        registry.register("C", recordingHandler("C", SUCCESS));

        Ctx ctx = new Ctx();
        ctx.steps.add(new Step("A"));
        ctx.steps.add(new Step("B"));
        ctx.steps.add(new Step("C"));

        Result result = new CastKernel<>(registry, SEMANTICS).run(ctx);
        assertSame(SUCCESS, result, "an all-pass walk returns the success result");
        assertEquals(List.of("A", "B", "C"), ctx.trace, "handlers ran in step order");
    }

    @Test
    void shortCircuitsOnFirstNonSuccessAndReturnsThatResult() {
        StepRegistry<String, Ctx, Step, Result> registry = new StepRegistry<>();
        Result failB = new Result(false, "FAIL_B");
        registry.register("A", recordingHandler("A", SUCCESS));
        registry.register("B", recordingHandler("B", failB));
        registry.register("C", recordingHandler("C", SUCCESS));

        Ctx ctx = new Ctx();
        ctx.steps.add(new Step("A"));
        ctx.steps.add(new Step("B"));
        ctx.steps.add(new Step("C"));

        Result result = new CastKernel<>(registry, SEMANTICS).run(ctx);
        assertSame(failB, result, "the first non-success result is returned verbatim");
        assertEquals(List.of("A", "B"), ctx.trace, "C never ran - the walk short-circuited at B");
    }

    @Test
    void missingHandlerInvokesTheHookAndReturnsItsResult() {
        StepRegistry<String, Ctx, Step, Result> registry = new StepRegistry<>();
        registry.register("A", recordingHandler("A", SUCCESS));
        // "B" is NOT registered.

        Ctx ctx = new Ctx();
        ctx.steps.add(new Step("A"));
        ctx.steps.add(new Step("B"));
        ctx.steps.add(new Step("C"));

        Result result = new CastKernel<>(registry, SEMANTICS).run(ctx);
        assertEquals("MISSING:B", result.label(), "the missing-handler hook produced the result");
        assertEquals(List.of("A", "MISSING:B"), ctx.trace, "the hook fired once, C never reached");
    }

    @Test
    void nullKeyIsTreatedAsMissingHandler() {
        StepRegistry<String, Ctx, Step, Result> registry = new StepRegistry<>();

        Ctx ctx = new Ctx();
        ctx.steps.add(new Step(null));

        Result result = new CastKernel<>(registry, SEMANTICS).run(ctx);
        assertEquals("MISSING:null", result.label(), "a null key routes to the missing-handler hook");
    }

    @Test
    void emptyChainSucceeds() {
        StepRegistry<String, Ctx, Step, Result> registry = new StepRegistry<>();
        Ctx ctx = new Ctx();

        Result result = new CastKernel<>(registry, SEMANTICS).run(ctx);
        assertSame(SUCCESS, result, "an empty step chain returns the success result");
        assertTrue(ctx.trace.isEmpty(), "no handlers ran");
    }

    // ==================== M4(c): ability-style run() is unaffected by the reshape ====================

    /**
     * Regression for critique M4(c). {@code SEMANTICS} above never overrides {@code isSuspend} /
     * {@code nextIndex} - the exact shape of the MMO ability caller's own {@code StepSemantics}
     * ({@code AbilityStepEngine.SEMANTICS}). Both {@link CastKernel#run} (now implemented over
     * {@link CastKernel#runResumable}) and {@code runResumable} itself must still short-circuit on
     * the first non-success step and never travel the new suspend/skip machinery.
     */
    @Test
    void run_abilityStyleSemantics_shortCircuitsIdenticallyAfterResumableReshape() {
        StepRegistry<String, Ctx, Step, Result> registry = new StepRegistry<>();
        Result failB = new Result(false, "FAIL_B");
        registry.register("A", recordingHandler("A", SUCCESS));
        registry.register("B", recordingHandler("B", failB));
        registry.register("C", recordingHandler("C", SUCCESS));
        CastKernel<Ctx, Step, String, Result> kernel = new CastKernel<>(registry, SEMANTICS);

        Ctx ctx = new Ctx();
        ctx.steps.add(new Step("A"));
        ctx.steps.add(new Step("B"));
        ctx.steps.add(new Step("C"));
        Result runResult = kernel.run(ctx);
        assertSame(failB, runResult, "run() still short-circuits on the first non-success result");
        assertEquals(List.of("A", "B"), ctx.trace, "C never ran under run()");

        Ctx ctx2 = new Ctx();
        ctx2.steps.add(new Step("A"));
        ctx2.steps.add(new Step("B"));
        ctx2.steps.add(new Step("C"));
        CastKernel.Walk<Result> walk = kernel.runResumable(ctx2, 0);
        if (walk instanceof CastKernel.Walk.Failed<Result> failed) {
            assertEquals(1, failed.atIndex(), "B is index 1");
            assertSame(failB, failed.result());
        } else {
            fail("boolean-only semantics (no isSuspend override) must never produce Suspended, got: " + walk);
        }
        assertEquals(List.of("A", "B"), ctx2.trace, "C never ran under runResumable() either");
    }

    // ==================== M4(a): no-catch contract + the fault-isolation seam ====================

    /**
     * The kernel does NOT catch a handler throw on {@code runResumable} any more than it did on the
     * pre-reshape {@code run} loop - a station-side (or any consumer-side) frame system that dispatches
     * inside a shared per-tick system is responsible for its OWN containment (see the seam test below),
     * not the kernel.
     */
    @Test
    void unwrappedHandlerThrow_propagatesOutOfRunResumable_noCatchContract() {
        StepRegistry<String, Ctx, Step, Result> registry = new StepRegistry<>();
        registry.register("A", recordingHandler("A", SUCCESS));
        registry.register("B", (ctx, step) -> {
            throw new IllegalStateException("boom");
        });

        Ctx ctx = new Ctx();
        ctx.steps.add(new Step("A"));
        ctx.steps.add(new Step("B"));

        CastKernel<Ctx, Step, String, Result> kernel = new CastKernel<>(registry, SEMANTICS);
        assertThrows(IllegalStateException.class, () -> kernel.runResumable(ctx, 0),
                "the kernel must not catch a handler throw - byte-parity with the pre-reshape loop");
    }

    /**
     * Proves the fault-isolation SEAM documented on {@link CastKernel}: a consumer that wants a
     * throwing step to fail only ITS OWN walk (not crash a shared per-world frame system) wraps the
     * {@link StepHandler} at REGISTRATION time, not the kernel's dispatch. Once the wrapper converts
     * the throw to a domain failure result, the kernel's ordinary non-success short-circuit produces
     * {@code Failed(atIndex, ...)} with the correct index for free - no kernel change needed. This is
     * the exact pattern the RpgStations station-side wrapper (leg B) applies to every registered
     * station step handler.
     */
    @Test
    void handlerWrappedAtRegistration_convertsThrowToFailedAtCorrectIndex_faultIsolationSeam() {
        StepRegistry<String, Ctx, Step, Result> registry = new StepRegistry<>();
        Result crashResult = new Result(false, "CRASHED:B");
        registry.register("A", recordingHandler("A", SUCCESS));
        registry.register("B", guarded("B", (ctx, step) -> {
            throw new IllegalStateException("boom");
        }, crashResult));
        registry.register("C", recordingHandler("C", SUCCESS));

        Ctx ctx = new Ctx();
        ctx.steps.add(new Step("A"));
        ctx.steps.add(new Step("B"));
        ctx.steps.add(new Step("C"));

        CastKernel.Walk<Result> walk = new CastKernel<>(registry, SEMANTICS).runResumable(ctx, 0);
        if (walk instanceof CastKernel.Walk.Failed<Result> failed) {
            assertEquals(1, failed.atIndex(), "the throwing step's own index, not lost to a propagating throw");
            assertSame(crashResult, failed.result());
        } else {
            fail("expected a Failed walk from the guarded handler's converted result, got: " + walk);
        }
        assertEquals(List.of("A", "GUARDED_CATCH:B"), ctx.trace, "C never ran - the walk still short-circuited");
    }

    /** Wraps {@code real} so a throw is caught, traced, and converted to {@code onThrow} instead of propagating. */
    private static StepHandler<Ctx, Step, Result> guarded(
            String token, StepHandler<Ctx, Step, Result> real, Result onThrow) {
        return (ctx, step) -> {
            try {
                return real.execute(ctx, step);
            } catch (RuntimeException e) {
                ctx.trace.add("GUARDED_CATCH:" + token);
                return onThrow;
            }
        };
    }

    // ==================== runResumable: suspend / resume / branch (design 9.3) ====================

    /** Station-style tri-state result kind: SUCCESS and SKIP both count as "continue" via isSuccess. */
    private enum Kind { SUCCESS, SKIP, SUSPEND, FAIL }

    private record TriResult(Kind kind, String label) { }

    /** Station-style step: just a key (the same shape as {@link Step}, kept separate to not entangle the two test domains). */
    private record StationStep(String key) { }

    /** Station-style context: a step list, an execution trace, a fake clock, and a session-held Wait deadline. */
    private static final class StationCtx {
        final List<StationStep> steps = new ArrayList<>();
        final List<String> trace = new ArrayList<>();
        long now;
        Long waitDeadline;
    }

    private static final TriResult TRI_SUCCESS = new TriResult(Kind.SUCCESS, "SUCCESS");

    private static final StepSemantics<StationCtx, StationStep, String, TriResult> STATION_SEMANTICS =
            new StepSemantics<>() {
                @Nonnull
                @Override
                public Iterable<StationStep> stepsOf(@Nonnull StationCtx ctx) {
                    return ctx.steps;
                }

                @Nullable
                @Override
                public String keyOf(@Nonnull StationStep step) {
                    return step.key();
                }

                @Override
                public boolean isSuccess(@Nonnull TriResult result) {
                    return result.kind() == Kind.SUCCESS || result.kind() == Kind.SKIP;
                }

                @Override
                public boolean isSuspend(@Nonnull TriResult result) {
                    return result.kind() == Kind.SUSPEND;
                }

                @Nonnull
                @Override
                public TriResult successResult(@Nonnull StationCtx ctx) {
                    return TRI_SUCCESS;
                }

                @Nonnull
                @Override
                public TriResult onMissingHandler(@Nonnull StationCtx ctx, @Nonnull StationStep step, @Nullable String key) {
                    ctx.trace.add("MISSING:" + key);
                    return new TriResult(Kind.FAIL, "MISSING:" + key);
                }
            };

    /** {@code STATION_SEMANTICS} with a custom {@code nextIndex} jump table, for the branching test. */
    private static StepSemantics<StationCtx, StationStep, String, TriResult> stationSemanticsWithGoto(
            Map<Integer, Integer> jumps) {
        return new StepSemantics<>() {
            @Nonnull
            @Override
            public Iterable<StationStep> stepsOf(@Nonnull StationCtx ctx) {
                return STATION_SEMANTICS.stepsOf(ctx);
            }

            @Nullable
            @Override
            public String keyOf(@Nonnull StationStep step) {
                return STATION_SEMANTICS.keyOf(step);
            }

            @Override
            public boolean isSuccess(@Nonnull TriResult result) {
                return STATION_SEMANTICS.isSuccess(result);
            }

            @Override
            public boolean isSuspend(@Nonnull TriResult result) {
                return STATION_SEMANTICS.isSuspend(result);
            }

            @Nonnull
            @Override
            public TriResult successResult(@Nonnull StationCtx ctx) {
                return STATION_SEMANTICS.successResult(ctx);
            }

            @Nonnull
            @Override
            public TriResult onMissingHandler(@Nonnull StationCtx ctx, @Nonnull StationStep step, @Nullable String key) {
                return STATION_SEMANTICS.onMissingHandler(ctx, step, key);
            }

            @Override
            public int nextIndex(@Nonnull StationCtx ctx, @Nonnull StationStep step, int currentIndex, @Nonnull TriResult result) {
                return jumps.getOrDefault(currentIndex, currentIndex + 1);
            }
        };
    }

    private static StepHandler<StationCtx, StationStep, TriResult> stationHandler(String token, TriResult result) {
        return (ctx, step) -> {
            ctx.trace.add(token);
            return result;
        };
    }

    @Test
    void runResumable_stepSignalsSuspend_returnsSuspendedAtItsOwnIndexAndStopsThere() {
        StepRegistry<String, StationCtx, StationStep, TriResult> registry = new StepRegistry<>();
        TriResult waitSignal = new TriResult(Kind.SUSPEND, "WAIT");
        registry.register("A", stationHandler("A", TRI_SUCCESS));
        registry.register("B", stationHandler("B", waitSignal));
        registry.register("C", stationHandler("C", TRI_SUCCESS));

        StationCtx ctx = new StationCtx();
        ctx.steps.add(new StationStep("A"));
        ctx.steps.add(new StationStep("B"));
        ctx.steps.add(new StationStep("C"));

        CastKernel.Walk<TriResult> walk =
                new CastKernel<>(registry, STATION_SEMANTICS).runResumable(ctx, 0);

        if (walk instanceof CastKernel.Walk.Suspended<TriResult> suspended) {
            assertEquals(1, suspended.resumeIndex(), "B (the suspending step) is index 1");
            assertSame(waitSignal, suspended.signal());
        } else {
            fail("expected a Suspended walk, got: " + walk);
        }
        assertEquals(List.of("A", "B"), ctx.trace, "C never ran - the walk suspended at B");
    }

    @Test
    void runResumable_resumeReEntersTheSuspendingStep_idempotentWaitPattern() {
        // M4(b): resumeIndex is the SUSPENDING step's own index, and a Wait-style step reads its
        // deadline from session state (ctx.waitDeadline) instead of re-deriving it, so re-entry is
        // idempotent and only converges once the externally-tracked clock passes the committed deadline.
        StepRegistry<String, StationCtx, StationStep, TriResult> registry = new StepRegistry<>();
        registry.register("A", stationHandler("A", TRI_SUCCESS));
        registry.register("WAIT", (ctx, step) -> {
            if (ctx.waitDeadline == null) {
                ctx.waitDeadline = ctx.now + 100; // committed ONCE, on first entry
            }
            if (ctx.now >= ctx.waitDeadline) {
                ctx.trace.add("WAIT:due");
                return TRI_SUCCESS;
            }
            ctx.trace.add("WAIT:suspend");
            return new TriResult(Kind.SUSPEND, "WAIT");
        });
        registry.register("C", stationHandler("C", TRI_SUCCESS));

        StationCtx ctx = new StationCtx();
        ctx.steps.add(new StationStep("A"));
        ctx.steps.add(new StationStep("WAIT"));
        ctx.steps.add(new StationStep("C"));
        ctx.now = 0L;

        CastKernel<StationCtx, StationStep, String, TriResult> kernel = new CastKernel<>(registry, STATION_SEMANTICS);

        // Frame 1: A runs, WAIT commits a deadline of 100 and suspends at its own index (1).
        CastKernel.Walk<TriResult> first = kernel.runResumable(ctx, 0);
        int resumeIndex = assertSuspendedAndGetIndex(first);
        assertEquals(1, resumeIndex);
        assertEquals(100L, ctx.waitDeadline.longValue(), "the deadline is committed on first entry");

        // Frame 2: re-enter WAIT before the deadline - idempotent, still suspends at the SAME index,
        // WITHOUT re-deriving a fresh deadline (would be 200 if it naively recomputed now+100).
        CastKernel.Walk<TriResult> second = kernel.runResumable(ctx, resumeIndex);
        int resumeIndex2 = assertSuspendedAndGetIndex(second);
        assertEquals(1, resumeIndex2);
        assertEquals(100L, ctx.waitDeadline.longValue(), "re-entry must not re-derive the deadline");

        // Frame 3: the clock passes the committed deadline - WAIT resumes AT index 1 and succeeds, then C runs.
        ctx.now = 150L;
        CastKernel.Walk<TriResult> third = kernel.runResumable(ctx, resumeIndex2);
        if (!(third instanceof CastKernel.Walk.Completed<TriResult>)) {
            fail("expected the walk to complete once the deadline passed, got: " + third);
        }
        assertEquals(List.of("A", "WAIT:suspend", "WAIT:suspend", "WAIT:due", "C"), ctx.trace,
                "WAIT was re-entered (not skipped past) on every resume, and only advanced once due");
    }

    private static int assertSuspendedAndGetIndex(CastKernel.Walk<TriResult> walk) {
        if (walk instanceof CastKernel.Walk.Suspended<TriResult> suspended) {
            return suspended.resumeIndex();
        }
        return fail("expected a Suspended walk, got: " + walk);
    }

    @Test
    void runResumable_nextIndexHookBranches_skipsStepsWithoutADedicatedBranchType() {
        StepRegistry<String, StationCtx, StationStep, TriResult> registry = new StepRegistry<>();
        registry.register("A", stationHandler("A", TRI_SUCCESS));
        registry.register("B", stationHandler("B", TRI_SUCCESS)); // must never run - skipped by the Goto
        registry.register("C", stationHandler("C", TRI_SUCCESS));

        StationCtx ctx = new StationCtx();
        ctx.steps.add(new StationStep("A"));
        ctx.steps.add(new StationStep("B"));
        ctx.steps.add(new StationStep("C"));

        // After step 0 (A) succeeds, jump straight to step 2 (C), skipping step 1 (B).
        StepSemantics<StationCtx, StationStep, String, TriResult> gotoSemantics =
                stationSemanticsWithGoto(Map.of(0, 2));

        CastKernel.Walk<TriResult> walk = new CastKernel<>(registry, gotoSemantics).runResumable(ctx, 0);

        if (!(walk instanceof CastKernel.Walk.Completed<TriResult>)) {
            fail("expected a Completed walk, got: " + walk);
        }
        assertEquals(List.of("A", "C"), ctx.trace, "B was skipped entirely via the nextIndex hook");
    }

    @Test
    void runResumable_missingHandler_returnsFailedAtTheStepsIndex() {
        StepRegistry<String, StationCtx, StationStep, TriResult> registry = new StepRegistry<>();
        registry.register("A", stationHandler("A", TRI_SUCCESS));
        // "B" is NOT registered.

        StationCtx ctx = new StationCtx();
        ctx.steps.add(new StationStep("A"));
        ctx.steps.add(new StationStep("B"));
        ctx.steps.add(new StationStep("C"));

        CastKernel.Walk<TriResult> walk =
                new CastKernel<>(registry, STATION_SEMANTICS).runResumable(ctx, 0);

        if (walk instanceof CastKernel.Walk.Failed<TriResult> failed) {
            assertEquals(1, failed.atIndex());
            assertEquals("MISSING:B", failed.result().label());
        } else {
            fail("expected a Failed walk, got: " + walk);
        }
    }

    @Test
    void runResumable_emptyOrAlreadyDoneChain_returnsCompletedAndRunsNothing() {
        StepRegistry<String, StationCtx, StationStep, TriResult> registry = new StepRegistry<>();
        CastKernel<StationCtx, StationStep, String, TriResult> kernel = new CastKernel<>(registry, STATION_SEMANTICS);

        StationCtx emptyCtx = new StationCtx();
        CastKernel.Walk<TriResult> emptyWalk = kernel.runResumable(emptyCtx, 0);
        assertTrue(emptyWalk instanceof CastKernel.Walk.Completed<TriResult>, "an empty chain completes");
        assertTrue(emptyCtx.trace.isEmpty());

        StationCtx doneCtx = new StationCtx();
        doneCtx.steps.add(new StationStep("A"));
        registry.register("A", stationHandler("A", TRI_SUCCESS));
        CastKernel.Walk<TriResult> doneWalk = kernel.runResumable(doneCtx, doneCtx.steps.size());
        assertTrue(doneWalk instanceof CastKernel.Walk.Completed<TriResult>,
                "a startIndex at (or past) the step count is a no-op success");
        assertTrue(doneCtx.trace.isEmpty(), "nothing ran - the chain was already exhausted");

        StationCtx negativeCtx = new StationCtx();
        negativeCtx.steps.add(new StationStep("A"));
        CastKernel.Walk<TriResult> negativeWalk = kernel.runResumable(negativeCtx, -1);
        assertTrue(negativeWalk instanceof CastKernel.Walk.Completed<TriResult>,
                "a negative startIndex is treated as nothing-left-to-walk, not a bounds exception");
        assertTrue(negativeCtx.trace.isEmpty());
    }
}
