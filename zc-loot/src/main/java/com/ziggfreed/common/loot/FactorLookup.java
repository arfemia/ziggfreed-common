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

    /**
     * Percent points this moment adds to every roll's written {@code Chance} ({@link RollEvaluator#effectiveChancePercent}),
     * before the 0..100 hold: a gathering moment's yield, say. 0 for an ordinary lookup; a roll that writes no
     * {@code Chance} is certain and takes nothing from it.
     */
    default double chanceBonusPercent() {
        return 0.0;
    }

    /** A lookup that answers nothing, so every gate over it fails closed and every sum reads 0. */
    @Nonnull
    static FactorLookup none() {
        return (factorId, param) -> null;
    }

    /**
     * {@code base}, answering the same readings and table question, with {@code percent} points added to every roll's
     * written {@code Chance} ({@link #chanceBonusPercent}). {@code base} itself when the bonus is 0 or not a number.
     */
    @Nonnull
    static FactorLookup withChanceBonus(@Nonnull FactorLookup base, double percent) {
        if (!Double.isFinite(percent) || percent == 0.0) {
            return base;
        }
        double bonus = base.chanceBonusPercent() + percent;
        return new FactorLookup() {
            @Nullable
            @Override
            public Double resolve(@Nonnull String factorId, @Nullable String param) {
                return base.resolve(factorId, param);
            }

            @Nullable
            @Override
            public GateQuery gateQuery() {
                return base.gateQuery();
            }

            @Override
            public double chanceBonusPercent() {
                return bonus;
            }
        };
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
