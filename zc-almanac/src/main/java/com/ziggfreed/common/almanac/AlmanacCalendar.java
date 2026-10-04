package com.ziggfreed.common.almanac;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Where one season stands, as the Almanac needs to know it: on or not, and the year the season on
 * right now opened in. Null means the calendar has no such event switched on, and the Almanac treats
 * that season as absent. The production answer is {@link FactorAlmanacCalendar}; a test hands in a
 * lambda.
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

    /** A calendar that knows no season: the Almanac lists nothing and counts nothing. */
    AlmanacCalendar NONE = eventId -> null;

    /** Where {@code eventId}'s season stands, or null when the calendar has no such event switched on. */
    @Nullable
    SeasonState state(@Nonnull String eventId);
}
