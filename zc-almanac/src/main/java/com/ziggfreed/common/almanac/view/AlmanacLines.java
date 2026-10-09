package com.ziggfreed.common.almanac.view;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Month;
import java.time.MonthDay;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.almanac.AlmanacText;
import com.ziggfreed.common.almanac.view.AlmanacView.Recurring;
import com.ziggfreed.common.almanac.view.AlmanacView.Scope;
import com.ziggfreed.common.almanac.view.AlmanacView.Season;
import com.ziggfreed.common.almanac.view.AlmanacView.Tally;
import com.ziggfreed.common.almanac.view.AlmanacView.Timing;
import com.ziggfreed.common.occurrence.Recurrence;

/**
 * What the Almanac SAYS about a season's dates, scope and numbers, as client-resolved lines, so every
 * surface that shows a season (its row, its hero chip, a consumer's tile) reads it the same way. Which
 * line a countdown takes is decided here: "On now - N days left" while it runs, "Last day" on its last
 * ("Today only" for a one-day event), "Returns in N days" up to {@value #RETURNS_COUNTDOWN_DAYS} days out,
 * "Returns October 1" further off, "Starts in N days" before its first run, "Between seasons" with no next
 * run. The dates line reads "Every year, ..." for days that are the same every year, naming the run's year
 * when its days move, and names a one-day event's day once. A season that comes round monthly or weekly says
 * how instead ("The first Sunday of every month, for 7 days"), and its next run ("Next: Oct 4 to Oct 10") is a
 * line of its own under it ({@link #next}).
 * Numbers bind typed, a month, a weekday and an ordinal nest as their own keys, and a year and a time of day
 * are text so no locale groups them.
 */
public final class AlmanacLines {

    /** Up to this many days out a return counts down; further off it names its day. */
    public static final int RETURNS_COUNTDOWN_DAYS = 30;

    private AlmanacLines() {
    }

    /** The season's status line: the hero's chip and the list row's second line. */
    @Nonnull
    public static Message chip(@Nonnull Timing timing) {
        if (timing.live()) {
            if (timing.lastDay()) {
                // A one-day event's only day is never called its last.
                return AlmanacText.line(oneDay(timing) ? "chip.today_only" : "chip.last_day");
            }
            return timing.daysLeft() == null
                    ? AlmanacText.line("status.live") : AlmanacText.line("chip.live", (long) timing.daysLeft());
        }
        Integer daysUntil = timing.daysUntil();
        if (timing.startsLater() && daysUntil != null) {
            return AlmanacText.line("chip.starts_in", (long) daysUntil);
        }
        if (daysUntil != null && daysUntil <= RETURNS_COUNTDOWN_DAYS) {
            return AlmanacText.line("chip.returns_in", (long) daysUntil);
        }
        LocalDate next = timing.nextStart();
        if (next != null) {
            return AlmanacText.line("chip.returns_on", month(next.getMonthValue()), (long) next.getDayOfMonth());
        }
        return AlmanacText.line("chip.between");
    }

    /**
     * "Every year, October 1 to November 3" for days that are the same every year; "In 2027, March 18 to
     * April 4" for days that move, naming the run the line frames; a one-day event names its day once
     * ("Every year, January 13", "In 2027, January 13"); null when the calendar gave no dates. A season that
     * comes round monthly or weekly says how in their place ("The first Sunday of every month, for 7 days"); its
     * next run is a line of its own ({@link #next}), so a long rule never cuts the next run's days off.
     */
    @Nullable
    public static Message window(@Nonnull Timing timing) {
        Recurring recurring = timing.recurring();
        if (recurring != null) {
            return recurrence(recurring.rule());
        }
        MonthDay start = timing.windowStart();
        MonthDay end = timing.windowEnd();
        if (start == null || end == null) {
            return null;
        }
        Integer year = timing.windowYear();
        if (start.equals(end)) {
            return year != null
                    ? AlmanacText.line("window.year.day", String.valueOf(year), month(start.getMonthValue()),
                            (long) start.getDayOfMonth())
                    : AlmanacText.line("window.day", month(start.getMonthValue()), (long) start.getDayOfMonth());
        }
        if (year != null) {
            return AlmanacText.line("window.year", String.valueOf(year), month(start.getMonthValue()),
                    (long) start.getDayOfMonth(), month(end.getMonthValue()), (long) end.getDayOfMonth());
        }
        return AlmanacText.line("window", month(start.getMonthValue()), (long) start.getDayOfMonth(),
                month(end.getMonthValue()), (long) end.getDayOfMonth());
    }

    /**
     * The line under the dates line of a season that comes round monthly or weekly: its next run's days ("Next: Oct
     * 4 to Oct 10", {@link #nextRun}); null for any other season and while no run is due.
     */
    @Nullable
    public static Message next(@Nonnull Timing timing) {
        Recurring recurring = timing.recurring();
        return recurring == null ? null : nextRun(recurring);
    }

    /**
     * How a monthly or weekly season comes round, "{when}, {how long}": "The first Sunday of every month, for 7
     * days", "On the 15th of March, June, September and December, for 2 days", "On the 1st of every other month,
     * until the next", "Every Sunday, 14:00 to 16:00" (a run of hours names its clock times), "Every Saturday, all
     * day", "Every other Monday, for 7 days".
     */
    @Nonnull
    public static Message recurrence(@Nonnull Recurrence rule) {
        Message when = switch (rule) {
            case Recurrence.Monthly monthly -> monthly(monthly);
            case Recurrence.Weekly weekly -> weekly(weekly);
        };
        return AlmanacText.line("recur", when, runLength(rule.length()));
    }

    /**
     * The next run's days, "Next: Oct 4 to Oct 10" ("Next: Oct 10" for one day), or its weekday and clock times
     * when it starts or ends at a time of day ("Next: Sunday Oct 11, 14:00 to 16:00"; both ends when it lasts a
     * day or more, "Next: Oct 9, 18:00 to Oct 11, 18:00"); null when no run is due.
     */
    @Nullable
    public static Message nextRun(@Nonnull Recurring recurring) {
        LocalDateTime start = recurring.nextStart();
        LocalDateTime end = recurring.nextEnd();
        if (start == null || end == null) {
            return null;
        }
        if (start.toLocalTime().equals(LocalTime.MIDNIGHT) && end.toLocalTime().equals(LocalTime.MIDNIGHT)) {
            LocalDate first = start.toLocalDate();
            // Whole days: the end is the midnight after the last.
            LocalDate last = end.toLocalDate().minusDays(1);
            if (!last.isAfter(first)) {
                return AlmanacText.line("next.day", shortMonth(first.getMonthValue()), (long) first.getDayOfMonth());
            }
            return AlmanacText.line("next", shortMonth(first.getMonthValue()), (long) first.getDayOfMonth(),
                    shortMonth(last.getMonthValue()), (long) last.getDayOfMonth());
        }
        if (Duration.between(start, end).compareTo(Duration.ofDays(1)) < 0) {
            return AlmanacText.line("next.hours", weekday(start.getDayOfWeek()), shortMonth(start.getMonthValue()),
                    (long) start.getDayOfMonth(), clock(start.toLocalTime()), clock(end.toLocalTime()));
        }
        return AlmanacText.line("next.span", shortMonth(start.getMonthValue()), (long) start.getDayOfMonth(),
                clock(start.toLocalTime()), shortMonth(end.getMonthValue()), (long) end.getDayOfMonth(),
                clock(end.toLocalTime()));
    }

    /** A weekday's name, Monday to Sunday. */
    @Nonnull
    public static Message weekday(@Nonnull DayOfWeek weekday) {
        return AlmanacText.line("weekday." + weekday.getValue());
    }

    /** "first" to "fifth", or "last" for {@link Recurrence#LAST}: the Nth weekday of a month. */
    @Nonnull
    public static Message nth(int nth) {
        return AlmanacText.line(nth == Recurrence.LAST ? "ordinal.nth.last"
                : "ordinal.nth." + Math.max(1, Math.min(5, nth)));
    }

    /** "1st" to "31st": a day of the month, as its own key so each locale writes its ordinals. */
    @Nonnull
    public static Message ordinalDay(int day) {
        return AlmanacText.line("ordinal.day." + Math.max(1, Math.min(31, day)));
    }

    /** "On the 15th of {months}" or "The first Sunday of {months}", in some months when it skips some too. */
    @Nonnull
    private static Message monthly(@Nonnull Recurrence.Monthly rule) {
        boolean listed = !rule.months().isEmpty();
        Message of = listed && rule.every() == 1 ? monthList(rule.months()) : everyMonths(rule.every());
        Message on = rule.weekday() != null
                ? AlmanacText.line("recur.monthly.weekday", nth(rule.nth()), weekday(rule.weekday()), of)
                : AlmanacText.line("recur.monthly.day", ordinalDay(rule.day()), of);
        return listed && rule.every() > 1 ? AlmanacText.line("recur.in_months", on, monthList(rule.months())) : on;
    }

    /** "every month", "every other month", "every 3 months". */
    @Nonnull
    private static Message everyMonths(int every) {
        if (every <= 1) {
            return AlmanacText.line("recur.months.every");
        }
        return every == 2 ? AlmanacText.line("recur.months.other") : AlmanacText.line("recur.months.every_n",
                (long) every);
    }

    /** "Every Sunday", "Every other Monday", "Every 3 weeks on Monday", then the months it runs in, if not all. */
    @Nonnull
    private static Message weekly(@Nonnull Recurrence.Weekly rule) {
        Message day = weekday(rule.weekday());
        Message every = rule.every() <= 1 ? AlmanacText.line("recur.weekly", day)
                : rule.every() == 2 ? AlmanacText.line("recur.weekly.other", day)
                : AlmanacText.line("recur.weekly.every_n", (long) rule.every(), day);
        return rule.months().isEmpty() ? every : AlmanacText.line("recur.in_months", every, monthList(rule.months()));
    }

    /**
     * "for 7 days", "all day", "14:00 to 16:00" (a length under a day, as clock times), "until the next", or
     * "from 18:00, ..." for whole days or until the next from a time of day. A length of a day or more is said in
     * the days it reaches into.
     */
    @Nonnull
    private static Message runLength(@Nonnull Recurrence.Length length) {
        String from = length.at() == null ? null : clock(length.at());
        if (length.untilNext()) {
            return from == null ? AlmanacText.line("recur.until_next")
                    : AlmanacText.line("recur.from.until_next", from);
        }
        Duration duration = length.duration();
        if (duration != null && duration.compareTo(Duration.ofDays(1)) < 0) {
            LocalTime start = length.at() == null ? LocalTime.MIDNIGHT : length.at();
            return AlmanacText.line("recur.hours", clock(start), clock(start.plus(duration)));
        }
        long days = duration == null ? length.days()
                : Math.ceilDiv(duration.toSeconds(), Duration.ofDays(1).toSeconds());
        if (from == null) {
            return days == 1 ? AlmanacText.line("recur.all_day") : AlmanacText.line("recur.for_days", days);
        }
        return AlmanacText.line("recur.from.for_days", from, days);
    }

    /** "March, June, September and December": each month its own key, joined by the list keys. */
    @Nonnull
    private static Message monthList(@Nonnull Set<Month> months) {
        List<Message> names = months.stream().map(month -> month(month.getValue())).toList();
        int last = names.size() - 1;
        if (last < 0) {
            return everyMonths(1);
        }
        if (last == 0) {
            return names.get(0);
        }
        Message joined = AlmanacText.line("list.pair", names.get(last - 1), names.get(last));
        for (int i = last - 2; i >= 0; i--) {
            joined = AlmanacText.line("list.more", names.get(i), joined);
        }
        return joined;
    }

    /** A time of day on the 24-hour clock, "14:00": text, the same in every locale. */
    @Nonnull
    private static String clock(@Nonnull LocalTime time) {
        return String.format(Locale.ROOT, "%02d:%02d", time.getHour(), time.getMinute());
    }

    /** A run of one day: its window starts and ends on the same month-day. */
    private static boolean oneDay(@Nonnull Timing timing) {
        MonthDay start = timing.windowStart();
        return start != null && start.equals(timing.windowEnd());
    }

    /** A month's name, 1 to 12. */
    @Nonnull
    public static Message month(int month) {
        return AlmanacText.line("month." + month);
    }

    /** A month's short name, 1 to 12, for the year at a glance. */
    @Nonnull
    public static Message shortMonth(int month) {
        return AlmanacText.line("month.short." + month);
    }

    /** "Your 2026 season", or "Every season". */
    @Nonnull
    public static Message scopeHeader(@Nonnull Scope scope) {
        return scope.every()
                ? AlmanacText.line("scope.every") : AlmanacText.line("scope.year", String.valueOf(scope.year()));
    }

    /**
     * The scope header's meta: "You took part" or "You have not taken part yet" for a year; "2 seasons
     * taken part in" for every season, else the same not-yet line.
     */
    @Nonnull
    public static Message scopeMeta(@Nonnull Scope scope, boolean tookPart, long seasonsTakenPart) {
        if (scope.every()) {
            return seasonsTakenPart > 0L
                    ? AlmanacText.line("scope.taken_part_count", seasonsTakenPart) : AlmanacText.line("scope.not_yet");
        }
        return AlmanacText.line(tookPart ? "scope.took_part" : "scope.not_yet");
    }

    /** A tile's caption, "1,204 in all", or null on every season. */
    @Nullable
    public static Message tileCaption(@Nonnull Tally tally) {
        return tally.allSeasons() == null ? null : AlmanacText.line("tile.in_all", (long) tally.allSeasons());
    }

    /** A tile's server line, "8,431 on this server", or null until someone on the server counted one. */
    @Nullable
    public static Message tileServer(@Nonnull Tally tally) {
        return tally.server() == null ? null : AlmanacText.line("tile.server", (long) tally.server());
    }

    /** The keepsake shelf's meta, "1 of 2 years". */
    @Nonnull
    public static Message keepsakesMeta(long earned, long years) {
        return AlmanacText.line("keepsakes.meta", earned, years);
    }

    /** The record card's caption under its seasons figure. */
    @Nonnull
    public static Message recordSeasons(long seasonsTakenPart) {
        return AlmanacText.line("record.seasons", seasonsTakenPart);
    }

    /** The record card's caption under its keepsakes figure. */
    @Nonnull
    public static Message recordKeepsakes(long keepsakes) {
        return AlmanacText.line("record.keepsakes", keepsakes);
    }

    /** "Hallow's Eve is on now", for a consumer's tile: one season by name. */
    @Nonnull
    public static Message headline(@Nonnull Season season) {
        return AlmanacText.line("headline.live", name(season));
    }

    /**
     * The tile line for every season on now, in list order: one by name, two by name, or the first by name and
     * how many more; null when none is on.
     */
    @Nullable
    public static Message headline(@Nonnull List<Season> live) {
        if (live.isEmpty()) {
            return null;
        }
        if (live.size() == 1) {
            return headline(live.get(0));
        }
        if (live.size() == 2) {
            return AlmanacText.line("headline.live.two", name(live.get(0)), name(live.get(1)));
        }
        return AlmanacText.line("headline.live.more", name(live.get(0)), (long) (live.size() - 1));
    }

    @Nonnull
    private static Message name(@Nonnull Season season) {
        return AlmanacText.authored(season.titleKey(), season.eventId());
    }
}
