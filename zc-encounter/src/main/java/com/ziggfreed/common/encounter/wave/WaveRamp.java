package com.ziggfreed.common.encounter.wave;

import javax.annotation.Nonnull;

/**
 * How much tougher each creature an endless run raises is: a multiplier on its maximum health and one on the damage
 * it deals, read off the wave it rose in. Pure.
 *
 * <p>A mob wave's multiplier is {@code 1 + perWave x (wave - 1)}, so the first wave is as its roles make it; a boss
 * wave's is {@code 1 + perReturn x (return - 1)}, so the boss's first return is as his role makes him and each
 * return after it is a step stronger. Either is held between 1 and {@link #cap}.
 *
 * <p>Health and damage reach the creature apart. Health folds into the one maximum-health modifier the encounter
 * already writes on each add (its party scale times this), since the engine sums every multiplicative modifier on a
 * stat rather than multiplying them ({@code EntityStatValue.computeModifiers}), so a second key would add to the
 * party scale instead of growing it. Damage goes on as a native effect's outgoing damage.
 *
 * @param healthPerWave   extra maximum health per wave after the first, as a fraction (0.08 is 8 percent)
 * @param damagePerWave   extra damage per wave after the first, as a fraction
 * @param healthPerReturn extra maximum health per boss return after the first
 * @param damagePerReturn extra damage per boss return after the first
 * @param cap             the most either multiplier reaches, 1 or more
 */
public record WaveRamp(double healthPerWave, double damagePerWave, double healthPerReturn, double damagePerReturn,
        double cap) {

    /** No ramp: every wave as its roles make it. */
    public static final WaveRamp NONE = new WaveRamp(0.0, 0.0, 0.0, 0.0, 1.0);

    /** The most a ramp ever reaches, whatever its file writes: what a ramp with no cap of its own is held to. */
    public static final double CEILING = 10.0;

    public WaveRamp {
        healthPerWave = finiteOrZero(healthPerWave);
        damagePerWave = finiteOrZero(damagePerWave);
        healthPerReturn = finiteOrZero(healthPerReturn);
        damagePerReturn = finiteOrZero(damagePerReturn);
        cap = Double.isFinite(cap) ? Math.max(1.0, Math.min(CEILING, cap)) : 1.0;
    }

    /** The maximum-health multiplier of a creature raised in {@code wave}. */
    public double health(@Nonnull WaveRules.Wave wave) {
        return grow(wave.boss() ? healthPerReturn : healthPerWave, steps(wave));
    }

    /** The damage multiplier of a creature raised in {@code wave}. */
    public double damage(@Nonnull WaveRules.Wave wave) {
        return grow(wave.boss() ? damagePerReturn : damagePerWave, steps(wave));
    }

    /** Whether this ramp ever makes anything tougher. */
    public boolean grows() {
        return cap > 1.0 && (healthPerWave > 0.0 || damagePerWave > 0.0 || healthPerReturn > 0.0
                || damagePerReturn > 0.0);
    }

    /** How many steps past the first a wave is: waves on a mob wave, returns on a boss wave. */
    private static int steps(@Nonnull WaveRules.Wave wave) {
        return Math.max(0, (wave.boss() ? wave.returns() : wave.number()) - 1);
    }

    private double grow(double perStep, int steps) {
        double value = 1.0 + perStep * steps;
        if (!Double.isFinite(value)) {
            return 1.0;
        }
        return Math.max(1.0, Math.min(cap, value));
    }

    private static double finiteOrZero(double value) {
        return Double.isFinite(value) ? value : 0.0;
    }
}
