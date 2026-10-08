package com.ziggfreed.common.calendar;

import java.time.Month;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.calendar.asset.CalendarEventAsset;
import com.ziggfreed.common.occurrence.Recurrence;

/**
 * A calendar rule as zc-core's neutral {@link Recurrence}, for a reader that never sees the calendar and says how an
 * event comes round ({@link CalendarService#recurrence}). Only a {@link YearRule.Monthly} or {@link YearRule.Weekly}
 * rule that can date several runs a year is one; any other rule (one run a year, several spans of month-days) and a
 * year whose days a Years entry sets out are not. Pure: no clock, no store.
 */
public final class RecurrenceOf {

    private RecurrenceOf() {
    }

    /**
     * How {@code event} comes round by the rule dating the year it is in at {@code nowMs} on its own clock (its
     * FirstYear's rule before then, since that one dates its first run), whatever its switches say; null for no
     * event, one that cannot run, and a year whose rule is no recurrence ({@link #rule}).
     */
    @Nullable
    public static Recurrence event(@Nullable CalendarEventAsset event, long nowMs) {
        if (event == null || !event.canRun()) {
            return null;
        }
        int year = Math.max(event.firstYear(), AnnualWindow.yearOf(nowMs, event.zone()));
        return rule(event.annualWindow().rule(year));
    }

    /** {@code rule} as a recurrence when it is monthly or weekly and dates several runs a year, else null. */
    @Nullable
    public static Recurrence rule(@Nullable YearRule rule) {
        if (rule == null || !rule.several()) {
            return null;
        }
        return switch (rule) {
            case YearRule.Monthly monthly -> monthly.weekday() != null
                    ? Recurrence.Monthly.onWeekday(monthly.nth(), monthly.weekday(), monthly.cadence().every(),
                            months(monthly.cadence()), length(monthly.length()))
                    : Recurrence.Monthly.onDay(monthly.day(), monthly.cadence().every(), months(monthly.cadence()),
                            length(monthly.length()));
            case YearRule.Weekly weekly -> new Recurrence.Weekly(weekly.weekday(), weekly.cadence().every(),
                    months(weekly.cadence()), length(weekly.length()));
            default -> null;
        };
    }

    /** The months a cadence names, none for every month. */
    @Nonnull
    private static Set<Month> months(@Nonnull YearRule.Cadence cadence) {
        return cadence.allYear() ? Set.of() : cadence.months();
    }

    /** A run's length as the calendar dates it: until the next wins over a length, a length over days. */
    @Nonnull
    private static Recurrence.Length length(@Nonnull YearRule.RunLength length) {
        if (length.untilNext()) {
            return Recurrence.Length.untilNext(length.at());
        }
        if (length.length() != null) {
            return Recurrence.Length.timed(length.at(), length.length());
        }
        return Recurrence.Length.days(length.days(), length.at());
    }
}
