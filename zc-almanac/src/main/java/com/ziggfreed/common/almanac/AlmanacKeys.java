package com.ziggfreed.common.almanac;

import java.util.Locale;
import java.util.SortedSet;
import java.util.TreeSet;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.counter.CounterMap;
import com.ziggfreed.common.counter.Counters;

/**
 * The ONE way a tally is named in a player's Almanac record. Every season's tally of a stat is
 * {@code <event>/<stat>}; one season's is {@code <event>@<year>/<stat>}. The category half is the
 * counter package's own ({@link Counters#key}), so the record reads like every other tally bag in the
 * library, and both halves are lower-cased so a key never splits on an author's casing.
 *
 * <p>A season or stat name may not carry {@code /} (the category separator), {@code @} (the year
 * mark), {@code |} or {@code :} (the save format's joins), nor start with {@code $} (reserved for the
 * Almanac's own tallies, {@link #ATTENDED}). {@link #usableId} is the one check.
 */
public final class AlmanacKeys {

    /** The tally a season's attendance is kept under. Never an authored stat name. */
    public static final String ATTENDED = "$attended";

    /** Joins a season's year to its event id in a one-season category. */
    public static final String YEAR_MARK = "@";

    private AlmanacKeys() {
    }

    /** The id as every key spells it: trimmed and lower-cased. */
    @Nonnull
    public static String normalize(@Nonnull String id) {
        return id.trim().toLowerCase(Locale.ROOT);
    }

    /** Can {@code id} name a season or a tally without breaking the record's format? */
    public static boolean usableId(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        String trimmed = id.trim();
        return !trimmed.startsWith("$") && trimmed.indexOf('/') < 0 && trimmed.indexOf('@') < 0
                && trimmed.indexOf('|') < 0 && trimmed.indexOf(':') < 0;
    }

    /** The every-season tally of {@code statId} for {@code eventId}. */
    @Nonnull
    public static String lifetime(@Nonnull String eventId, @Nonnull String statId) {
        return Counters.key(normalize(eventId), normalize(statId));
    }

    /** The one-season tally of {@code statId} for the season of {@code eventId} that opened in {@code year}. */
    @Nonnull
    public static String season(@Nonnull String eventId, int year, @Nonnull String statId) {
        return Counters.key(normalize(eventId) + YEAR_MARK + year, normalize(statId));
    }

    /** Every season year this record holds a tally for under {@code eventId}, oldest first. */
    @Nonnull
    public static SortedSet<Integer> seasonYears(@Nonnull CounterMap tallies, @Nonnull String eventId) {
        String prefix = normalize(eventId) + YEAR_MARK;
        SortedSet<Integer> years = new TreeSet<>();
        for (String key : tallies.keys()) {
            String folded = key.toLowerCase(Locale.ROOT);
            if (!folded.startsWith(prefix)) {
                continue;
            }
            int slash = folded.indexOf(Counters.CATEGORY_SEPARATOR, prefix.length());
            if (slash <= prefix.length()) {
                continue;
            }
            try {
                years.add(Integer.parseInt(folded.substring(prefix.length(), slash)));
            } catch (NumberFormatException ignored) {
                // Not a year: a key this scheme never writes, so it names no season.
            }
        }
        return years;
    }
}
