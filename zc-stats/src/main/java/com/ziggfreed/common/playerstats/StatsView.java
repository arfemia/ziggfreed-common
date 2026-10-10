package com.ziggfreed.common.playerstats;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.counter.CounterMap;
import com.ziggfreed.common.counter.Counters;

/**
 * A frozen, read-only copy of one player's stat record, safe to hand anywhere: nothing a reader does to
 * it reaches the record, and a later count never changes it. Every read matches a key without regard
 * to case, the way the record does, and an absent tally reads {@code 0}.
 */
public final class StatsView {

    /** The view of a player with no record. */
    public static final StatsView EMPTY = new StatsView(Map.of(), Map.of());

    private final CounterMap tallies;
    private final CounterMap seasons;

    StatsView(@Nonnull Map<String, Long> tallies, @Nonnull Map<String, Long> seasons) {
        this.tallies = CounterMap.of(tallies);
        this.seasons = CounterMap.of(seasons);
    }

    /** One lifetime tally by its full key ({@code Mob_Kills} or {@code Mob_Kills/Skeleton}), or {@code 0}. */
    public long get(@Nullable String key) {
        return tallies.get(key);
    }

    /** A stat's grand total, or one line of its breakdown when {@code thing} is named; {@code 0} when absent. */
    public long get(@Nonnull String statId, @Nullable String thing) {
        return tallies.get(StatKeys.line(statId, thing));
    }

    /**
     * A stat's whole breakdown, keyed by the thing half (the {@code <stat>/} prefix stripped), in key
     * order; empty when the player has counted nothing in it.
     */
    @Nonnull
    public Map<String, Long> breakdown(@Nonnull String statId) {
        String prefix = StatKeys.total(statId) + Counters.CATEGORY_SEPARATOR;
        Map<String, Long> stored = tallies.all();
        Map<String, Long> out = new LinkedHashMap<>();
        for (String key : new TreeSet<>(stored.keySet())) {
            if (key.length() > prefix.length() && key.regionMatches(true, 0, prefix, 0, prefix.length())) {
                out.put(key.substring(prefix.length()), stored.get(key));
            }
        }
        return out;
    }

    /** Every lifetime tally, keyed as stored. */
    @Nonnull
    public Map<String, Long> all() {
        return tallies.all();
    }

    /** One per season-year tally by its full key ({@code <event>@<year>/<stat key>}), or {@code 0}. */
    public long season(@Nullable String key) {
        return seasons.get(key);
    }

    /** Every per season-year tally, keyed as stored. */
    @Nonnull
    public Map<String, Long> seasons() {
        return seasons.all();
    }

    /** True when the record holds no tally at all. */
    public boolean isEmpty() {
        return tallies.isEmpty() && seasons.isEmpty();
    }
}
