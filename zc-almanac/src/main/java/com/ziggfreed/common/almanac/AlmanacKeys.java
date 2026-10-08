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
 * Almanac's own tallies, {@link #ATTENDED}, {@link #RUNS} and {@link #OWNED}). {@link #usableId} is the one check.
 */
public final class AlmanacKeys {

    /** The tally a season's attendance is kept under. Never an authored stat name. */
    public static final String ATTENDED = "$attended";

    /** The tally of how many runs of a season-year were attended (an event may come round several times a year). */
    public static final String RUNS = "$runs";

    /** The category a player's "owned once" marks file under, one key per item a page's collection lists. */
    public static final String OWNED = "$owned";

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

    /** The mark that {@code itemId} was obtained once: {@code $owned/<item>}, lower-cased. */
    @Nonnull
    public static String owned(@Nonnull String itemId) {
        return Counters.key(OWNED, normalize(itemId));
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

    /**
     * How many runs of {@code eventId} that began in {@code year} this record attended: its run tally, or 1 for a
     * year attended before runs were counted (every such year had one run).
     */
    public static long runsAttended(@Nonnull CounterMap tallies, @Nonnull String eventId, int year) {
        long runs = tallies.get(season(eventId, year, RUNS));
        if (runs > 0L) {
            return runs;
        }
        return tallies.get(season(eventId, year, ATTENDED)) > 0L ? 1L : 0L;
    }
}
