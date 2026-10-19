package com.ziggfreed.common.track.gain;

import javax.annotation.Nonnull;

import com.ziggfreed.common.track.GainCall;

/**
 * The factors a track adds together ({@code Gain.Additive}, the MMO's Bonus and Boost unless a track says
 * otherwise), as one step at the place the first of them stands: each member runs on the same amount, and their
 * gains add, so a Bonus of 1.2 beside a Boost of 1.5 pays {@code 1 + 0.2 + 0.5 = 1.7} times, never
 * {@code 1.2 * 1.5 = 1.8}. That is the MMO's {@code bonusMultiplier + (boostMultiplier - 1)} to the bit, for any
 * number of members.
 *
 * <p>It says each member's part itself ({@link #explainsItself}): every member but the last as its own multiplier,
 * the last as whatever makes the product the group's whole, so a breakdown still multiplies out to what landed
 * (the MMO's split: the bonus as it is, the boost absorbing the rest). A member that throws, or answers no number,
 * changes nothing, warned once.
 */
final class AdditiveStep implements GainStep {

    @Nonnull private final String[] ids;
    @Nonnull private final GainStep[] members;

    AdditiveStep(@Nonnull String[] ids, @Nonnull GainStep[] members) {
        this.ids = ids;
        this.members = members;
    }

    @Override
    public double apply(@Nonnull GainCall call, double amount) {
        double[] parts = call.explaining() ? new double[members.length] : null;
        double total = amount;
        for (int i = 0; i < members.length; i++) {
            double out;
            try {
                out = members[i].apply(call, amount);
            } catch (Throwable t) {
                GainPlans.warnOnce(ids[i], "failed on a gain", t);
                out = amount;
            }
            if (!Double.isFinite(out)) {
                out = amount;
            }
            total += out - amount;
            if (parts != null) {
                parts[i] = amount == 0.0 ? 1.0 : out / amount;
            }
        }
        total = Math.max(0.0, total);
        if (parts != null) {
            explain(call, parts, amount == 0.0 ? 1.0 : total / amount);
        }
        return total;
    }

    @Override
    public boolean explainsItself() {
        return true;
    }

    /** Each member's own multiplier, the last carrying what makes their product {@code whole}. */
    private void explain(@Nonnull GainCall call, @Nonnull double[] parts, double whole) {
        int last = parts.length - 1;
        double before = 1.0;
        for (int i = 0; i < last; i++) {
            call.explain(ids[i], parts[i]);
            before *= parts[i];
        }
        call.explain(ids[last], before == 0.0 ? whole : whole / before);
    }
}
