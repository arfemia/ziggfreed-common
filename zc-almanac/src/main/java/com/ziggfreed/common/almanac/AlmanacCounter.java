package com.ziggfreed.common.almanac;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.counter.CounterMap;
import com.ziggfreed.common.progress.ZoneRef;

/**
 * What one produced moment adds to one player's Almanac record. Pure: it takes the record's bag, the
 * stat index and the calendar, and touches nothing else.
 *
 * <ul>
 *   <li>A matching line while its season is on ADDS the moment's amount to the season's tally and to
 *       the every-season tally.</li>
 *   <li>Between seasons only a {@code LiveOnly: false} line counts, and only toward every season.</li>
 *   <li>A season the calendar does not answer for counts nothing (off means absent).</li>
 *   <li>{@value #ATTENDED_KIND} marks the live season attended once (a high-water 1), adds one to the
 *       runs it attended that season-year (the calendar fires once per player per run, and an event may
 *       come round several times a year; a year marked attended before runs were counted starts from
 *       its one run), and, the first time each season, adds one to the seasons attended.</li>
 *   <li>An amount of zero or less never counts: a tally never goes down.</li>
 * </ul>
 */
public final class AlmanacCounter {

    /** The calendar's attendance moment; its target is the event id. */
    public static final String ATTENDED_KIND = "CALENDAR_ATTENDED";

    private AlmanacCounter() {
    }

    public static void count(@Nonnull CounterMap tallies, @Nonnull AlmanacIndex index,
            @Nonnull AlmanacCalendar calendar, @Nonnull String kindId, @Nonnull String target,
            @Nullable String qualifier, long amount, @Nullable ZoneRef zone) {
        if (ATTENDED_KIND.equalsIgnoreCase(kindId)) {
            attend(tallies, calendar, target);
            return;
        }
        if (amount <= 0L) {
            return;
        }
        for (AlmanacIndex.Line line : index.forKind(kindId)) {
            if (!line.def().matches(target, qualifier) || !line.def().matchesZone(zone)) {
                continue;
            }
            AlmanacCalendar.SeasonState state = calendar.state(line.eventId());
            if (state == null) {
                continue;
            }
            if (state.live()) {
                tallies.add(AlmanacKeys.lifetime(line.eventId(), line.statId()), amount);
                tallies.add(AlmanacKeys.season(line.eventId(), state.year(), line.statId()), amount);
            } else if (!line.liveOnly()) {
                tallies.add(AlmanacKeys.lifetime(line.eventId(), line.statId()), amount);
            }
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
