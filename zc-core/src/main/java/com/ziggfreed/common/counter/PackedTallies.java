package com.ziggfreed.common.counter;

import java.util.Map;
import java.util.TreeMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The one string a saved tally bag travels as: {@code key:value|key:value}, keys sorted so a save is
 * stable. A record that keeps its tallies in one string field (the Almanac's, the player stat store's)
 * reads and writes through here, so a new tally is a new key rather than a new field and a save
 * written before a tally existed still reads.
 *
 * <p><b>Damage costs only itself.</b> A value is split from its key at the LAST colon, so a key may
 * hold a colon; a pair that is not {@code key:number} is dropped on read and every other tally
 * survives. A key may not hold {@value #PAIR_SEPARATOR}: {@link #usableKey} is the check a writer
 * makes before it files one.
 */
public final class PackedTallies {

    /** Joins one pair to the next. Reserved inside a key. */
    public static final char PAIR_SEPARATOR = '|';

    /** Joins a key to its value; the last one in a pair is the join. */
    public static final char VALUE_SEPARATOR = ':';

    private PackedTallies() {
    }

    /** {@code key:value|key:value}, keys sorted so a save is stable; an empty bag is an empty string. */
    @Nonnull
    public static String serialize(@Nullable Map<String, Long> tallies) {
        if (tallies == null || tallies.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Long> entry : new TreeMap<>(tallies).entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            if (out.length() > 0) {
                out.append(PAIR_SEPARATOR);
            }
            out.append(entry.getKey()).append(VALUE_SEPARATOR).append(entry.getValue());
        }
        return out.toString();
    }

    /** The bag a saved string holds; a damaged pair is dropped, never the rest. */
    @Nonnull
    public static CounterMap deserialize(@Nullable String packed) {
        CounterMap out = new CounterMap();
        if (packed == null || packed.isEmpty()) {
            return out;
        }
        for (String pair : packed.split("\\|")) {
            int colon = pair.lastIndexOf(VALUE_SEPARATOR);
            if (colon <= 0 || colon == pair.length() - 1) {
                continue;
            }
            try {
                out.set(pair.substring(0, colon), Long.parseLong(pair.substring(colon + 1)));
            } catch (NumberFormatException ignored) {
                // Not key:number: this pair is lost, every other tally survives.
            }
        }
        return out;
    }

    /** Can {@code key} be saved without breaking the string? Not blank and free of {@value #PAIR_SEPARATOR}. */
    public static boolean usableKey(@Nullable String key) {
        return key != null && !key.isBlank() && key.indexOf(PAIR_SEPARATOR) < 0;
    }
}
