package com.ziggfreed.common.cost;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * How a price GROWS with each purchase: the curve, how steep it is, and where it stops steepening.
 *
 * <p>The RUNTIME value, not the authored shape; the authoring layer folds its own group into one of
 * these.
 *
 * <p>{@link Curve} is a union DISCRIMINATOR over growth shapes rather than a mode: it selects which
 * of two formulas turns a purchase count into a factor and toggles nothing else. The multiplier and
 * the soft cap are ordinary knobs beside it, each meaningful under either curve.
 *
 * <ul>
 *   <li>{@link Curve#EXPONENTIAL}: {@code base * multiplier^count}. A multiplier of 1.10 makes
 *       every purchase ten percent dearer than the last.</li>
 *   <li>{@link Curve#POLYNOMIAL}: {@code base * (count + 1)^multiplier}. Gentler at high counts, so
 *       an endlessly repeatable offer plateaus instead of running away.</li>
 * </ul>
 *
 * <p>A soft cap above zero FREEZES the curve at that count: every purchase past it costs the same
 * (already very large) amount rather than continuing to climb.
 */
public final class CostScaling {

    /** The two growth shapes a price may take. */
    public enum Curve {
        EXPONENTIAL,
        POLYNOMIAL
    }

    private final Curve curve;
    private final double multiplier;
    private final int softCap;

    private CostScaling(@Nonnull Curve curve, double multiplier, int softCap) {
        this.curve = curve;
        this.multiplier = (Double.isFinite(multiplier) && multiplier > 0.0) ? multiplier : 1.0;
        this.softCap = Math.max(0, softCap);
    }

    /** A growth curve; a non-positive multiplier reads as one, which leaves the price flat. */
    @Nonnull
    public static CostScaling of(@Nonnull Curve curve, double multiplier, int softCap) {
        return new CostScaling(curve, multiplier, softCap);
    }

    @Nonnull
    public Curve curve() {
        return curve;
    }

    /** How steep the curve is; one leaves the price flat under either curve. */
    public double multiplier() {
        return multiplier;
    }

    /** The purchase count the curve freezes at; zero means it never does. */
    public int softCap() {
        return softCap;
    }

    /**
     * What the {@code (count + 1)}-th purchase costs, where {@code count} is how many have already
     * been made. A count of zero or a null scaling answers the base price unchanged, so a caller
     * never has to branch on whether an offer scales.
     */
    public static long scaled(long base, int count, @Nullable CostScaling scaling) {
        if (scaling == null || count <= 0 || base <= 0L) {
            return base;
        }
        int cap = scaling.softCap();
        int effective = (cap > 0 && count > cap) ? cap : count;
        double multiplier = scaling.multiplier();
        double value = switch (scaling.curve()) {
            case EXPONENTIAL -> base * Math.pow(multiplier, effective);
            case POLYNOMIAL -> base * Math.pow(effective + 1.0, multiplier);
        };
        if (!Double.isFinite(value) || value >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, Math.round(value));
    }

    @Override
    public String toString() {
        return "CostScaling[" + curve + " x" + multiplier + (softCap > 0 ? ", softCap=" + softCap : "") + "]";
    }
}
