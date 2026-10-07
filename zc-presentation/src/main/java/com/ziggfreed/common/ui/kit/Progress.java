package com.ziggfreed.common.ui.kit;

/** A count toward a total, for a bar: {@code current} of {@code total}. */
public record Progress(long current, long total) {

    /** How far along, 0 to 1; 0 with no total. */
    public float fraction() {
        if (total <= 0) {
            return 0f;
        }
        double f = (double) Math.max(0, current) / total;
        return (float) Math.min(1.0, f);
    }

    /** Whether the count reached its total (a total of 0 never completes). */
    public boolean complete() {
        return total > 0 && current >= total;
    }
}
