package com.ziggfreed.common.factor;

import java.util.List;
import java.util.function.Function;

import javax.annotation.Nullable;

/**
 * Whether a FILE loads on this server at all: false only when a plain top-level
 * {@code hytale:mod_installed} condition with {@code Min} of 1 or more names a mod this server does not
 * run. Every keyed store applies it in its load handler, so such a file never reaches a fold, an index,
 * a draw, a contribution, a validator or an audit: content that pays a companion mod is simply not
 * there without it, and nothing logs about it.
 *
 * <p><b>Only a definite absence drops.</b> {@link ModFactors#installed} answers {@code 0} for a mod no
 * table knows and {@code null} when it cannot tell (a malformed {@code Param}, no plugin table yet). A
 * {@code null} keeps the file: the live hide axis ({@code FeatureLift} in zc-progression) still reads it
 * as off, so it hides rather than loads on a guess.
 *
 * <p><b>Only the plain form is a gate.</b> A bounds-less condition passes on the {@code 0} an absent mod
 * reads, so it gates nothing and drops nothing; author {@code Min: 1}. A condition with a {@code Max}, or
 * a {@code Min} below 1, is a requirement (the "only where it is NOT installed" half among them), and
 * stays where it was written. A condition nested in {@code AnyOf} or {@code Not} is never handed here:
 * a store passes its TOP-LEVEL {@code Factors} only.
 */
public final class ModGates {

    /** What a mod's presence reads as: the factor's own reading, unless a test swapped it. */
    private static volatile Function<String, Double> probe = ModFactors::installed;

    private ModGates() {
    }

    /** {@link #keep(FactorCondition[])} over a list. */
    public static boolean keep(@Nullable List<FactorCondition> topLevelFactors) {
        return topLevelFactors == null || keep(topLevelFactors.toArray(new FactorCondition[0]));
    }

    /**
     * Does a file whose top-level {@code Factors} are {@code topLevelFactors} load here? False when any
     * of them is a presence gate ({@link #isPresenceGate}) on a mod that reads a definite 0.
     */
    public static boolean keep(@Nullable FactorCondition[] topLevelFactors) {
        if (topLevelFactors == null) {
            return true;
        }
        for (FactorCondition condition : topLevelFactors) {
            if (isPresenceGate(condition) && isAbsent(condition.getParam())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Is {@code condition} the plain "that mod is installed" form: {@code hytale:mod_installed} (any
     * case), no {@code Max}, and a {@code Min} of 1 or more?
     */
    public static boolean isPresenceGate(@Nullable FactorCondition condition) {
        if (condition == null || condition.isBlank() || condition.getMax() != null) {
            return false;
        }
        Double min = condition.getMin();
        return min != null && min >= 1.0
                && ModFactors.MOD_INSTALLED.equalsIgnoreCase(condition.getFactor().trim());
    }

    /** A definite 0 from the probe; a null, a throw or anything else is "cannot tell". */
    private static boolean isAbsent(@Nullable String param) {
        Double reading;
        try {
            reading = probe.apply(param);
        } catch (Throwable t) {
            return false;
        }
        return reading != null && reading.doubleValue() == 0.0;
    }

    /** Answer presence through {@code testProbe} instead; null puts the factor's own reading back. */
    public static void useProbeForTests(@Nullable Function<String, Double> testProbe) {
        probe = testProbe == null ? ModFactors::installed : testProbe;
    }
}
