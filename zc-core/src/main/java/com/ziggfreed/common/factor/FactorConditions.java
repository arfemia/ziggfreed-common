package com.ziggfreed.common.factor;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The ONE evaluator for a whole {@code Conditions} array: every entry must pass. Every consumer
 * that authors {@link FactorCondition}s calls this rather than writing its own loop, so
 * "all must pass", "a blank entry is skipped", and "which one failed" mean the same thing at
 * every site.
 *
 * <p>A BLANK entry (no factor id authored) is SKIPPED rather than failing the gate: a
 * half-authored line is an authoring slip, and hiding otherwise-working content behind it makes
 * that slip much harder to find than a validator finding does. Everything else fails closed - see
 * {@link FactorCondition#accepts}.
 */
public final class FactorConditions {

    private FactorConditions() {
    }

    /**
     * The factor id of the FIRST condition that did not pass, or {@code null} when every entry
     * passed (or there was nothing to evaluate). The caller turns a non-null answer into its own
     * gate reason, so the message can name the factor that actually shut the gate.
     */
    @Nullable
    public static String firstFailure(@Nullable List<FactorCondition> conditions,
            @Nonnull FactorRegistry registry, @Nonnull FactorContext ctx) {
        if (conditions == null || conditions.isEmpty()) {
            return null;
        }
        for (FactorCondition condition : conditions) {
            String failed = firstFailure(condition, registry, ctx);
            if (failed != null) {
                return failed;
            }
        }
        return null;
    }

    /** The array form of {@link #firstFailure(List, FactorRegistry, FactorContext)}. */
    @Nullable
    public static String firstFailure(@Nullable FactorCondition[] conditions,
            @Nonnull FactorRegistry registry, @Nonnull FactorContext ctx) {
        if (conditions == null || conditions.length == 0) {
            return null;
        }
        for (FactorCondition condition : conditions) {
            String failed = firstFailure(condition, registry, ctx);
            if (failed != null) {
                return failed;
            }
        }
        return null;
    }

    /**
     * One condition that did not pass, together with the reading the evaluation resolved for it -
     * null when the factor could not be answered at all, which is itself why the condition failed.
     * The value rides along so a surface quoting "what the player currently has" never re-resolves
     * anything: the number is the very one the decision was made on.
     */
    public record Failure(@Nonnull FactorCondition condition, @Nullable Double resolved) {
    }

    /**
     * EVERY condition that did not pass, in authored order, empty when they all did. The whole array
     * is walked rather than short-circuited, for a caller listing everything still in the way
     * instead of naming the next thing to go and do.
     *
     * <p>The failing CONDITION comes back rather than its factor id, so the caller already holds the
     * {@code Param} and the bound it needs to write the sentence, and never has to look a condition
     * back up by id - which is ambiguous the moment one block bounds the same factor twice.
     */
    @Nonnull
    public static List<FactorCondition> allFailures(@Nullable List<FactorCondition> conditions,
            @Nonnull FactorRegistry registry, @Nonnull FactorContext ctx) {
        return conditionsOf(allFailuresResolved(conditions, registry, ctx));
    }

    /** The array form of {@link #allFailures(List, FactorRegistry, FactorContext)}. */
    @Nonnull
    public static List<FactorCondition> allFailures(@Nullable FactorCondition[] conditions,
            @Nonnull FactorRegistry registry, @Nonnull FactorContext ctx) {
        return conditionsOf(allFailuresResolved(conditions, registry, ctx));
    }

    /**
     * {@link #allFailures(List, FactorRegistry, FactorContext)} with each failure carrying the
     * reading it was decided on - same walk, one resolution per condition.
     */
    @Nonnull
    public static List<Failure> allFailuresResolved(@Nullable List<FactorCondition> conditions,
            @Nonnull FactorRegistry registry, @Nonnull FactorContext ctx) {
        if (conditions == null || conditions.isEmpty()) {
            return List.of();
        }
        List<Failure> failed = new ArrayList<>();
        for (FactorCondition condition : conditions) {
            collectFailure(condition, registry, ctx, failed);
        }
        return failed;
    }

    /** The array form of {@link #allFailuresResolved(List, FactorRegistry, FactorContext)}. */
    @Nonnull
    public static List<Failure> allFailuresResolved(@Nullable FactorCondition[] conditions,
            @Nonnull FactorRegistry registry, @Nonnull FactorContext ctx) {
        if (conditions == null || conditions.length == 0) {
            return List.of();
        }
        List<Failure> failed = new ArrayList<>();
        for (FactorCondition condition : conditions) {
            collectFailure(condition, registry, ctx, failed);
        }
        return failed;
    }

    @Nonnull
    private static List<FactorCondition> conditionsOf(@Nonnull List<Failure> failures) {
        if (failures.isEmpty()) {
            return List.of();
        }
        List<FactorCondition> conditions = new ArrayList<>(failures.size());
        for (Failure failure : failures) {
            conditions.add(failure.condition());
        }
        return conditions;
    }

    /** Evaluate ONE condition for the collect-all walk, keeping the reading when it fails. */
    private static void collectFailure(@Nullable FactorCondition condition,
            @Nonnull FactorRegistry registry, @Nonnull FactorContext ctx, @Nonnull List<Failure> out) {
        if (condition == null || condition.isBlank()) {
            return;
        }
        FactorContext scoped = ctx.withParam(condition.getParam());
        Double resolved = registry.resolve(condition.getFactor(), scoped);
        if (!condition.accepts(resolved)) {
            out.add(new Failure(condition, resolved));
        }
    }

    /** True when every entry passed - the boolean wrapper for a caller with no reason to report. */
    public static boolean pass(@Nullable List<FactorCondition> conditions,
            @Nonnull FactorRegistry registry, @Nonnull FactorContext ctx) {
        return firstFailure(conditions, registry, ctx) == null;
    }

    /** The array form of {@link #pass(List, FactorRegistry, FactorContext)}. */
    public static boolean pass(@Nullable FactorCondition[] conditions,
            @Nonnull FactorRegistry registry, @Nonnull FactorContext ctx) {
        return firstFailure(conditions, registry, ctx) == null;
    }

    /**
     * Evaluate ONE condition. The context is rebuilt per entry with that entry's own
     * {@code Param}, so two conditions in the same array can address the same factor differently.
     */
    @Nullable
    private static String firstFailure(@Nullable FactorCondition condition,
            @Nonnull FactorRegistry registry, @Nonnull FactorContext ctx) {
        if (condition == null || condition.isBlank()) {
            return null;
        }
        FactorContext scoped = ctx.withParam(condition.getParam());
        return condition.accepts(registry.resolve(condition.getFactor(), scoped))
                ? null
                : condition.getFactor();
    }
}
