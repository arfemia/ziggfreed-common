package com.ziggfreed.common.instance.result;

import com.ziggfreed.common.util.NumberFormatter;

/**
 * How a {@link ScoreColumn}'s raw {@code long} value is rendered on the results screen.
 * Keeps the formatting game-agnostic: the consumer tags each column with a format and the
 * page renders it, so number/time/percent display never re-derives per page.
 */
public enum ColumnFormat {

    /** Thousands-grouped (e.g. 12,500) - the default for scores. */
    GROUPED,
    /** Compact (e.g. 12.5k). */
    COMPACT,
    /** Seconds rendered as m:ss. */
    TIME,
    /** A percentage (the value is the whole-number percent, e.g. 87 -> "87%"). */
    PERCENT,
    /** The raw number, unformatted. */
    RAW;

    /** Render {@code value} per this format to a display string. */
    public String render(long value) {
        return switch (this) {
            case GROUPED -> NumberFormatter.grouped(value);
            case COMPACT -> NumberFormatter.compact(value, 1000L);
            case TIME -> time(value);
            case PERCENT -> value + "%";
            case RAW -> Long.toString(value);
        };
    }

    private static String time(long seconds) {
        long s = Math.max(0, seconds);
        long m = s / 60;
        long rem = s % 60;
        return m + ":" + (rem < 10 ? "0" + rem : Long.toString(rem));
    }
}
