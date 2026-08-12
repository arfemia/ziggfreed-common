package com.ziggfreed.common.progress.gate;

import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Function;

import javax.annotation.Nonnull;

import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.subject.Subject;

/**
 * A requirement kind a mod registers under its own namespaced id, so content can author a friendly
 * form of a gate instead of spelling it out as raw factor bounds:
 *
 * <pre>{@code
 * "Requires": { "Custom": { "yourmod:trade_rank": { "Trade": "smithing", "Min": "10" } } }
 * }</pre>
 *
 * <p>Two ways to provide one, both ending at the same evaluation:
 * <ul>
 *   <li>{@link #desugaring} - turn the authored parameters into ordinary {@link FactorCondition}s
 *       and let the shared factor evaluation answer them. Prefer this: the gate then behaves
 *       exactly like a hand-written {@code Factors} entry, including failing closed when the
 *       factor cannot be answered.</li>
 *   <li>{@link #evaluating} - answer the question directly, for a requirement that genuinely is
 *       not a number ("has this player been to the north camp").</li>
 * </ul>
 *
 * <p>A kind must be cheap and side-effect free: it is asked every time a listing is built, not
 * once per accept.
 */
@FunctionalInterface
public interface GateKind {

    /**
     * Does {@code subject} satisfy this requirement with the authored {@code params}?
     *
     * @param support the shared evaluation seam, for a kind that answers by producing conditions
     */
    boolean passes(@Nonnull Subject subject, @Nonnull Map<String, String> params,
            @Nonnull GateSupport support);

    /**
     * A kind that turns its parameters into factor conditions, ALL of which must pass. The mapping
     * runs per evaluation, so a kind may read its parameters however it likes; returning an empty
     * list means "nothing to check", which passes.
     */
    @Nonnull
    static GateKind desugaring(@Nonnull Function<Map<String, String>, List<FactorCondition>> toConditions) {
        return (subject, params, support) -> support.factorsPass(subject, toConditions.apply(params));
    }

    /** A kind that answers the question itself, with no factor vocabulary involved. */
    @Nonnull
    static GateKind evaluating(@Nonnull BiPredicate<Subject, Map<String, String>> test) {
        return (subject, params, support) -> test.test(subject, params);
    }

    /**
     * The evaluation seam handed to a kind: how this library answers a list of factor conditions
     * for a subject. A kind never builds its own factor registry lookup, so a desugared gate and a
     * hand-authored one always agree.
     */
    @FunctionalInterface
    interface GateSupport {

        /** Do ALL of {@code conditions} pass for {@code subject}? An empty list passes. */
        boolean factorsPass(@Nonnull Subject subject, @Nonnull List<FactorCondition> conditions);
    }
}
