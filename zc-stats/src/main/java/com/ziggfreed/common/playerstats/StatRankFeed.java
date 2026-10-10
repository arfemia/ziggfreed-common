package com.ziggfreed.common.playerstats;

import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Where a stat write is ranked, beside the player's own record: the one place the store hands the stat
 * board what moved. {@link PlayerStats} feeds it after every write it keeps, and the ready pass feeds it
 * the player's whole record. Each map is {@code tally key -> number} under {@link StatKeys}; the board
 * decides which keys it ranks and which periods a write reaches.
 *
 * <p>The library fills it at setup with the stat board; {@link #NONE} is the answer before that, and a
 * feed that throws costs only its own ranking, never the count.
 */
public interface StatRankFeed {

    /** Ranks nothing. */
    StatRankFeed NONE = new StatRankFeed() {
        @Override
        public void add(@Nonnull UUID uuid, @Nullable String name, @Nonnull Map<String, Long> deltas) {
        }

        @Override
        public void raise(@Nonnull UUID uuid, @Nullable String name, @Nonnull Map<String, Long> values) {
        }

        @Override
        public void lower(@Nonnull UUID uuid, @Nullable String name, @Nonnull Map<String, Long> values) {
        }

        @Override
        public void setAllTime(@Nonnull UUID uuid, @Nullable String name, @Nonnull Map<String, Long> values) {
        }
    };

    /** A Sum moved: add each delta to the all-time ranking and every running period. */
    void add(@Nonnull UUID uuid, @Nullable String name, @Nonnull Map<String, Long> deltas);

    /** A Max moved: raise each value where it beats the ranking's. */
    void raise(@Nonnull UUID uuid, @Nullable String name, @Nonnull Map<String, Long> values);

    /** A Min moved: lower each value where it beats the ranking's. */
    void lower(@Nonnull UUID uuid, @Nullable String name, @Nonnull Map<String, Long> values);

    /** A Gauge, a native reading, or the ready re-sync: set each value on the all-time ranking outright. */
    void setAllTime(@Nonnull UUID uuid, @Nullable String name, @Nonnull Map<String, Long> values);
}
