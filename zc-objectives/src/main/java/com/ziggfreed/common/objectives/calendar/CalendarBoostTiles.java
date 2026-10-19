package com.ziggfreed.common.objectives.calendar;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;

import com.ziggfreed.common.calendar.tick.CalendarTick;
import com.ziggfreed.common.track.boost.Boosts;

/**
 * Re-syncs the boost tiles when the calendar changes, so a timed boost whose {@code Season} just ended (or whose
 * {@code Requires} reads the calendar) leaves the buff bar the moment it stops lifting, and one whose season just
 * began shows its tile again, with no wait for the player's next login, respawn or start.
 *
 * <p>It hears the calendar's own look ({@code CalendarTicker.listen}), the one that fires
 * {@code CalendarEventStartedEvent} and {@code CalendarEventEndedEvent}, rather than those two events, because an
 * event live by a condition (a Moon or a Live) rises and lapses with no native event; every path that changes
 * whether an event runs (its dates, {@code /zigcalendar force}, an owner switch, a pack reload, the boot's resumed
 * start, a world's moon) reaches that look. A quiet look (nothing began or ended) costs one check; a change hands
 * the events it moved to zc-track's {@link Boosts#onCalendarChange}, which re-syncs only when a timed boost
 * names one of them as its {@code Season} or asks a {@code Requires}, and does nothing when none does. Nothing here
 * runs on a gain.
 *
 * <p>The look runs on the calendar's tick thread, never a world thread: the re-sync only queues onto each player's
 * own world thread.
 */
public final class CalendarBoostTiles {

    private CalendarBoostTiles() {
    }

    /** One look at the calendar: the boost tiles re-sync when it moved an event a boost reads. */
    static void onTick(@Nonnull CalendarTick tick) {
        if (!tick.changed()) {
            return;
        }
        Boosts.onCalendarChange(changedIds(tick));
    }

    /** Every event id {@code tick} saw begin or end, a run or a condition, in the order it lists them. Pure. */
    @Nonnull
    static List<String> changedIds(@Nonnull CalendarTick tick) {
        List<String> out = new ArrayList<>();
        for (CalendarTick.Started started : tick.started()) {
            out.add(started.occurrence().eventId());
        }
        for (CalendarTick.Ended ended : tick.ended()) {
            out.add(ended.occurrence().eventId());
        }
        out.addAll(tick.rose());
        for (CalendarTick.Lapsed lapsed : tick.lapsed()) {
            out.add(lapsed.eventId());
        }
        return out;
    }
}
