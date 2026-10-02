package com.ziggfreed.common.loot;

import java.util.function.DoubleSupplier;

import javax.annotation.Nonnull;

/**
 * The ONE rule for turning a fractional amount into a whole count: the whole part every time, and
 * the fraction left over as the chance of exactly one more. {@code 1.5} pays one always and a second
 * half the time, so it averages exactly {@code 1.5}; {@code 0.25} pays one a quarter of the time and
 * none otherwise.
 *
 * <p>It is what a consumer reaches for whenever an authored or computed amount is fractional but what
 * it hands over has to be whole: a tally of bonus items summed over a moment, a multiplier read as a
 * number of passes over a loot block. Sum first and resolve once: two halves resolved separately
 * round twice and average zero or two, where their sum averages one.
 *
 * <p>Pure. The randomness is the caller's {@code [0,1)} source, consulted AT MOST ONCE and only when
 * there is a fraction to decide, so a whole amount stays deterministic and a pinned or seeded source
 * makes the answer reproducible.
 */
public final class StochasticCount {

    private StochasticCount() {
    }

    /**
     * {@code floor(amount)}, plus one more when {@code sample} draws strictly under the leftover
     * fraction.
     *
     * <ul>
     *   <li>A non-positive or non-finite amount (zero, negative, NaN, either infinity) resolves to
     *       {@code 0} and never draws.</li>
     *   <li>A whole amount never draws.</li>
     *   <li>The count saturates at {@link Integer#MAX_VALUE} rather than wrapping: a whole part past
     *       the int range reads as {@code Integer.MAX_VALUE}, and the extra one is never added on top
     *       of it (the draw is still made when there is a fraction, so a caller's draw count does not
     *       depend on the magnitude).</li>
     * </ul>
     *
     * @param amount the fractional amount to resolve, already summed by the caller
     * @param sample a uniform {@code [0,1)} source, consulted at most once
     */
    public static int resolve(double amount, @Nonnull DoubleSupplier sample) {
        if (!Double.isFinite(amount) || amount <= 0.0) {
            return 0;
        }
        double whole = Math.floor(amount);
        double fraction = amount - whole;
        int count = (int) Math.min(Integer.MAX_VALUE, whole);
        if (fraction > 0.0 && sample.getAsDouble() < fraction && count < Integer.MAX_VALUE) {
            count++;
        }
        return count;
    }
}
