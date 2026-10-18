package com.ziggfreed.common.loot;

import java.util.function.BiFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorRegistry;
import com.ziggfreed.common.progress.gate.GateQuery;

/**
 * How the loot layer asks for a factor reading: {@code (factorId, param) -> value}, with
 * {@code null} meaning "nobody could answer that".
 *
 * <p>Everything under this package evaluates through a lookup rather than reaching for a
 * {@link FactorRegistry} directly, and that indirection buys three things at once. A test drives a
 * roll off a fixture map with no server anywhere in sight. A whole batch of rolls shares ONE
 * {@link FactorSnapshot}, so a factor two rolls both read is resolved once. And an engine that
 * already resolves factors its own way plugs straight in.
 */
@FunctionalInterface
public interface FactorLookup {

    /** The reading for {@code factorId} under {@code param}, or null when it cannot be answered. */
    @Nullable
    Double resolve(@Nonnull String factorId, @Nullable String param);

    /** This lookup as the {@link BiFunction} shape {@code FactorFormula}'s own arithmetic takes. */
    @Nonnull
    default BiFunction<String, String, Double> asFunction() {
        return this::resolve;
    }

    /**
     * The question a table's {@code Requires} is put as for this moment ({@link FactorGate#passesGates}): its
     * context, with this lookup's own vocabulary answering first for its ids. Null for a lookup that knows
     * no moment, which refuses every table gate.
     */
    @Nullable
    default GateQuery gateQuery() {
        return null;
    }

    /** A lookup that answers nothing, so every gate over it fails closed and every sum reads 0. */
    @Nonnull
    static FactorLookup none() {
        return (factorId, param) -> null;
    }

    /** A live lookup straight through {@code registry}, re-scoping {@code ctx} to each term's own param. */
    @Nonnull
    static FactorLookup through(@Nonnull FactorRegistry registry, @Nonnull FactorContext ctx) {
        return new FactorLookup() {
            @Nullable
            @Override
            public Double resolve(@Nonnull String factorId, @Nullable String param) {
                return registry.resolve(factorId, ctx.withParam(param));
            }

            @Nonnull
            @Override
            public GateQuery gateQuery() {
                return GateQuery.builder().context(ctx).local(registry).build();
            }
        };
    }
}
