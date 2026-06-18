package com.ziggfreed.common.instance.preset;

import java.util.Locale;

import javax.annotation.Nullable;

/**
 * How an instance's leaderboard partitions its entries. {@link #PARTY_SIZE} preserves
 * Kweebec's existing per-playercount boards (the default); {@link #PRESET} buckets by
 * the preset/difficulty; {@link #GLOBAL} is one shared board. The consumer's
 * {@code Leaderboard} maps this to its bucket key.
 */
public enum LeaderboardBucket {

    PARTY_SIZE,
    PRESET,
    GLOBAL;

    /** Parse a codec/string value; unknown / null falls back to {@link #PARTY_SIZE}. */
    public static LeaderboardBucket fromString(@Nullable String s) {
        if (s == null || s.isBlank()) {
            return PARTY_SIZE;
        }
        try {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return PARTY_SIZE;
        }
    }
}
