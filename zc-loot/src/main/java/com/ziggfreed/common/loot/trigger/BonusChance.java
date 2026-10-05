package com.ziggfreed.common.loot.trigger;

import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorFormula;
import com.ziggfreed.common.factor.FactorRegistry;

/**
 * How likely a bonus row is, and whether one draw fires it.
 *
 * <p>A row's {@code Chance} is a PERCENT ({@code {"Base": 5}} is five percent, never 0.05): Base
 * plus each factor's value times its Weight, held inside its Clamp. A row that writes none takes its
 * moment's own default, which its table decides (certainty for a library row). The answer is always
 * a fraction held inside 0..1, and a result that is not a number is no chance at all, never a
 * certainty. Every bonus table reads its odds through here, so the arithmetic is written once.
 */
public final class BonusChance {

    private BonusChance() {
    }

    /**
     * The odds a row fires at, as a FRACTION in 0..1.
     *
     * @param chance    the row's own percent formula, or null for none
     * @param unwritten the moment's default when the row writes none, as a fraction
     * @param sourceId  the row's id, for the line a formula that cannot be read leaves
     */
    public static double fraction(@Nullable FactorFormula chance, double unwritten,
            @Nonnull FactorRegistry factors, @Nonnull FactorContext ctx, @Nonnull String sourceId,
            @Nonnull Consumer<String> warn) {
        double fallback = clamp(unwritten);
        if (chance == null || chance.isEmpty()) {
            return fallback;
        }
        try {
            return clamp(chance.evaluate(factors, ctx) / 100.0);
        } catch (Throwable t) {
            // Defensive: evaluate is documented never to throw; a vocabulary that breaks anyway
            // costs this row its own odds and nothing else.
            warn.accept("Bonus row '" + sourceId + "' has a Chance formula that could not be read, so it"
                    + " falls back to its moment's default: " + t.getMessage());
            return fallback;
        }
    }

    /**
     * Whether a row with {@code fraction} odds fires on this draw: at or below 0 (or not a number)
     * never, at or above 1 always, both without a draw; in between, exactly one draw, and the row
     * fires when it lands strictly below.
     */
    public static boolean fires(double fraction, @Nonnull DoubleSupplier draw) {
        if (!(fraction > 0.0)) {
            return false;
        }
        return fraction >= 1.0 || draw.getAsDouble() < fraction;
    }

    /** {@code value} held inside 0..1; NaN reads as 0, since a min/max clamp alone keeps it. */
    static double clamp(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}
