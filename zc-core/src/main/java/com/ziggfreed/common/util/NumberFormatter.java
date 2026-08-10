package com.ziggfreed.common.util;

import javax.annotation.Nonnull;

/**
 * The single source for numeric display formatting. Consumers delegate here instead
 * of each defining a private {@code formatXp} / {@code formatCount} / {@code grouped}
 * helper. Pure Java, zero engine coupling.
 */
public final class NumberFormatter {

    private NumberFormatter() {
    }

    /** Grouped thousands: {@code 1200 -> "1,200"}. */
    @Nonnull
    public static String grouped(long amount) {
        return String.format("%,d", amount);
    }

    /**
     * Compact magnitude with one decimal: {@code >= 1,000,000 -> "1.2M"},
     * {@code >= kThreshold -> "1.2k"}, otherwise the plain integer. {@code kThreshold}
     * is the smallest value that compresses to "k" (call sites use 1_000 or 10_000).
     */
    @Nonnull
    public static String compact(long value, long kThreshold) {
        if (value >= 1_000_000) {
            return String.format("%.1fM", value / 1_000_000.0);
        }
        if (value >= kThreshold) {
            return String.format("%.1fk", value / 1_000.0);
        }
        return String.valueOf(value);
    }
}
