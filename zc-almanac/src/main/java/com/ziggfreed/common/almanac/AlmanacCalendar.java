package com.ziggfreed.common.almanac;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.occurrence.Occurrence;
import com.ziggfreed.common.occurrence.Recurrence;

/**
 * Where one season stands, as the Almanac needs to know it: on or not, and the year the season on
 * right now opened in. Null means the calendar has no such event switched on, and the Almanac treats
 * that season as absent. The production answer is {@link OccurrenceAlmanacCalendar}; a test hands in a
 * lambda, which knows no dates ({@link #dates} answers {@link Dates#UNKNOWN}).
 */
@FunctionalInterface
public interface AlmanacCalendar {

    /** Where a season stands. {@code year} is the live season's opening year, and 0 between seasons. */
    record SeasonState(boolean live, int year) {

        /** Known to the calendar, not on right now. */
        public static final SeasonState BETWEEN = new SeasonState(false, 0);

        /** On right now, in the season that opened in {@code year}. */
        @Nonnull
        public static SeasonState liveIn(int year) {
            return new SeasonState(true, year);
        }
    }

    /**
     * What the calendar knows of a season's runs at one moment: the run going on (forces counted), the
     * next run to start, every run begun so far oldest first (the one going on included), the first year,
     * the clock its days are counted in, whether its days move from year to year, and, for a season that comes
     * round monthly or weekly, how it recurs ({@code recurrence}, null otherwise). A run's {@code endMs} is the
     * midnight after its last day, or the first instant after it for a run with a time of day.
     */
    record Dates(@Nullable Occurrence live, @Nullable Occurrence next, @Nonnull List<Occurrence> history,
                 @Nullable Integer firstYear, @Nonnull ZoneId zone, boolean datesMove,
                 @Nullable Recurrence recurrence) {

        /** Nothing known: no runs, no years, counted in UTC. What an absent season or a dateless calendar says. */
        public static final Dates UNKNOWN = new Dates(null, null, List.of(), null, ZoneOffset.UTC);

        public Dates {
            history = history == null ? List.of() : List.copyOf(history);
            zone = zone == null ? ZoneOffset.UTC : zone;
        }

        /** Days that are the same every year. */
        public Dates(@Nullable Occurrence live, @Nullable Occurrence next, @Nonnull List<Occurrence> history,
                @Nullable Integer firstYear, @Nonnull ZoneId zone) {
            this(live, next, history, firstYear, zone, false, null);
        }

        /** A season that does not come round monthly or weekly, its days moving from year to year or not. */
        public Dates(@Nullable Occurrence live, @Nullable Occurrence next, @Nonnull List<Occurrence> history,
                @Nullable Integer firstYear, @Nonnull ZoneId zone, boolean datesMove) {
            this(live, next, history, firstYear, zone, datesMove, null);
        }
    }

    /** A calendar that knows no season: the Almanac lists nothing and counts nothing. */
    AlmanacCalendar NONE = eventId -> null;

    /** Where {@code eventId}'s season stands, or null when the calendar has no such event switched on. */
    @Nullable
    SeasonState state(@Nonnull String eventId);

    /**
     * The season's runs as the calendar knows them at {@code nowMs}, for the dates and the countdown a page
     * shows. {@link Dates#UNKNOWN} for an absent season, and from a calendar that knows no dates.
     */
    @Nonnull
    default Dates dates(@Nonnull String eventId, long nowMs) {
        return Dates.UNKNOWN;
    }
}
