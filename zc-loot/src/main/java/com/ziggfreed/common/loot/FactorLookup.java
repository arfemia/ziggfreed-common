package com.ziggfreed.common.loot;

import java.util.function.BiFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorRegistry;

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

    /** A lookup that answers nothing, so every gate over it fails closed and every sum reads 0. */
    @Nonnull
    static FactorLookup none() {
        return (factorId, param) -> null;
    }

    /** A live lookup straight through {@code registry}, re-scoping {@code ctx} to each term's own param. */
    @Nonnull
    static FactorLookup through(@Nonnull FactorRegistry registry, @Nonnull FactorContext ctx) {
        return (factorId, param) -> registry.resolve(factorId, ctx.withParam(param));
    }
}
