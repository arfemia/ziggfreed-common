package com.ziggfreed.common.almanac;

import javax.annotation.Nonnull;

import com.ziggfreed.common.counter.CounterMap;

/**
 * What one produced moment adds to one player's Almanac record: attendance, the Almanac's own record. Pure:
 * it takes the record's bag and the calendar, and touches nothing else. A season page's stat lines count in
 * the player stat store instead ({@link AlmanacStatLines}).
 *
 * <ul>
 *   <li>{@value #ATTENDED_KIND} marks the live season attended once (a high-water 1), adds one to the
 *       runs it attended that season-year (the calendar fires once per player per run, and an event may
 *       come round several times a year; a year marked attended before runs were counted starts from
 *       its one run), and, the first time each season, adds one to the seasons attended.</li>
 *   <li>A season the calendar does not answer for, or one not on now, marks nothing (off means absent).</li>
 *   <li>Any other moment adds nothing here.</li>
 * </ul>
 */
public final class AlmanacCounter {

    /** The calendar's attendance moment; its target is the event id. */
    public static final String ATTENDED_KIND = "CALENDAR_ATTENDED";

    private AlmanacCounter() {
    }

    public static void count(@Nonnull CounterMap tallies, @Nonnull AlmanacCalendar calendar, @Nonnull String kindId,
            @Nonnull String target) {
        if (ATTENDED_KIND.equalsIgnoreCase(kindId)) {
            attend(tallies, calendar, target);
        }
    }

    private static void attend(@Nonnull CounterMap tallies, @Nonnull AlmanacCalendar calendar,
            @Nonnull String target) {
        String eventId = AlmanacKeys.normalize(target);
        if (!AlmanacKeys.usableId(eventId)) {
            return;
        }
        AlmanacCalendar.SeasonState state = calendar.state(eventId);
        if (state == null || !state.live()) {
            return;
        }
        // The calendar fires one attendance per player per run, so each is one more run of the season-year; the
        // season-year itself is marked once, and the seasons attended count years.
        String runs = AlmanacKeys.season(eventId, state.year(), AlmanacKeys.RUNS);
        String attended = AlmanacKeys.season(eventId, state.year(), AlmanacKeys.ATTENDED);
        if (tallies.get(runs) == 0L && tallies.get(attended) > 0L) {
            // A year marked attended by a build that counted no runs reads as one run (AlmanacKeys.runsAttended):
            // that run is counted first, so this one is the second.
            tallies.add(runs, 1L);
        }
        tallies.add(runs, 1L);
        if (tallies.highWater(attended, 1L)) {
            tallies.add(AlmanacKeys.lifetime(eventId, AlmanacKeys.ATTENDED), 1L);
        }
    }
}
