package com.ziggfreed.common.cast.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

/**
 * Characterization tests for the generic {@link CastKernel} walk. Uses purely test-local domain
 * types (a context holding a step list, a keyed step, a success/failure result) wired through a
 * {@link StepSemantics}, so nothing engine-side is needed. Verifies order, short-circuit, the
 * missing-handler hook, and the empty-chain success case - the byte-parity contract a consumer's
 * hand-written dispatch loop must reproduce.
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
}
