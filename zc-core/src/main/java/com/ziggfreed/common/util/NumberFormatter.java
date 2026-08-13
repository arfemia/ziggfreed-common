package com.ziggfreed.common.util;

import java.util.Locale;

import javax.annotation.Nonnull;

/**
 * The single source for numeric display formatting. Consumers delegate here instead
 * of each defining a private {@code formatXp} / {@code formatCount} / {@code grouped}
 * helper. Pure Java, zero engine coupling.
 *
 * <p>Every {@code String.format} here is pinned to {@link Locale#ROOT}, so the digit
 * grouping and the decimal mark are a property of this library rather than of whichever
 * locale the server JVM happens to boot in. Two servers running the same build then print
 * the same number the same way, and a formatted value baked into a client-resolved
 * {@code Message} never carries one machine's regional punctuation to every viewer.
 */
public final class NumberFormatter {

    private NumberFormatter() {
    }

    /** Grouped thousands: {@code 1200 -> "1,200"}. */
    @Nonnull
    public static String grouped(long amount) {
        return String.format(Locale.ROOT, "%,d", amount);
    }

    /**
     * Compact magnitude with one decimal: {@code >= 1,000,000 -> "1.2M"},
     * {@code >= kThreshold -> "1.2k"}, otherwise the plain integer. {@code kThreshold}
     * is the smallest value that compresses to "k" (call sites use 1_000 or 10_000).
     */
    @Nonnull
    public static String compact(long value, long kThreshold) {
        if (value >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1fM", value / 1_000_000.0);
        }
        if (value >= kThreshold) {
            return String.format(Locale.ROOT, "%.1fk", value / 1_000.0);
        }
        return String.valueOf(value);
    }
}
