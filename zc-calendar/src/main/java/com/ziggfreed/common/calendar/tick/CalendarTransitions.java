package com.ziggfreed.common.calendar.tick;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import javax.annotation.Nonnull;

import com.ziggfreed.common.occurrence.Occurrence;

/** What changed between two looks at the calendar. Pure. */
public final class CalendarTransitions {

    private CalendarTransitions() {
    }

    /**
     * Runs present only {@code now} start; runs present only {@code before} end; a run whose year changed
     * ends and its successor starts. An end is a switch-off when {@code enabled} now says no for its event.
     */
    @Nonnull
    public static CalendarTick diff(@Nonnull Map<String, Occurrence> before, @Nonnull Map<String, Occurrence> now,
            @Nonnull Predicate<String> enabled, long nowMs, boolean booting) {
        List<CalendarTick.Ended> ended = new ArrayList<>();
        for (Map.Entry<String, Occurrence> previous : before.entrySet()) {
            Occurrence current = now.get(previous.getKey());
            if (current == null || current.year() != previous.getValue().year()) {
                ended.add(new CalendarTick.Ended(previous.getValue(), !enabled.test(previous.getKey())));
            }
        }
        List<CalendarTick.Started> started = new ArrayList<>();
        for (Map.Entry<String, Occurrence> current : now.entrySet()) {
            Occurrence previous = before.get(current.getKey());
            if (previous == null || previous.year() != current.getValue().year()) {
                started.add(new CalendarTick.Started(current.getValue(), booting));
            }
        }
        return new CalendarTick(nowMs, booting, now.keySet(), started, ended);
    }
}
