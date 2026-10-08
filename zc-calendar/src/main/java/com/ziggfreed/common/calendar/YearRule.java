package com.ziggfreed.common.calendar;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Month;
import java.time.MonthDay;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * How each year's runs are dated:
 * <ul>
 *   <li>the same month-days every year (one span, or several);</li>
 *   <li>a span around Easter Sunday or around the Nth weekday of a month;</li>
 *   <li>a run each month, or each week.</li>
 * </ul>
 * Pure: no clock, no zone, no store.
 *
 * <p>{@link #runs} gives a year's runs in the rule's own order, and that order is each run's NUMBER (from 1):
 * a list of spans in the order written, a repeating rule in the order of the months or weeks it runs in. The
 * window never re-sorts them, so a re-date never renumbers a run.
 *
 * <p>A rule is {@linkplain #valid valid} only when EVERY year's runs start in that year and no run of the rule
 * ever meets its next one, in the same year or the year after. That lets a run's first instant name its year,
 * and keeps one event's runs from overlapping: the facts every reader of the calendar keys a run by. Several
 * spans ({@link FixedRuns}) are the one shape whose runs may meet one another; the window sets the later
 * written aside.
 *
 * <p>The repeating rules ({@link Monthly}, {@link Weekly}) take a {@link Cadence} (every Nth week or month
 * from an anchor, in some months) and a {@link RunLength} (whole days, hours from a time of day, or until the
 * next run). Times are on the event's own clock.
 */
public sealed interface YearRule permits YearRule.Fixed, YearRule.FixedRuns, YearRule.Easter, YearRule.Weekday,
        YearRule.Monthly, YearRule.Weekly {

    /** The most days one run may last, both ends in. */
    int MAX_RUN_DAYS = 366;

    /**
     * The most days a run of a repeating rule may last: a year, one day short of {@link #MAX_RUN_DAYS}, so that a
     * run starting late on December 31st still ends inside the next year, where the window looks for it.
     */
    int REPEATING_MAX_RUN_DAYS = 365;

    /** A year with no February 29th: there a day-of-year count runs lowest for every day after February. */
    int COMMON_YEAR = 2001;

    /** The fewest days from one Easter Sunday to the next (1974 to 1975 is one such pair, from 1970 to 9999). */
    int EASTER_MIN_GAP_DAYS = 350;

    /** The fewest days from the Nth weekday of a month to the same the year after: 52 weeks. */
    int WEEKDAY_MIN_GAP_DAYS = 364;

    /** The most days a monthly run may last, per month its Every spans: the fewest days between two monthly starts. */
    int MONTHLY_MAX_DAYS = 28;

    /** The most days a weekly run may last, per week its Every spans. */
    int WEEKLY_MAX_DAYS = 7;

    /** The most months apart a monthly run lasting until the next may be: a year. */
    int MONTHLY_MAX_EVERY_UNTIL_NEXT = 12;

    /** The most weeks apart a weekly run lasting until the next may be: a year. */
    int WEEKLY_MAX_EVERY_UNTIL_NEXT = 52;

    /** Every month of the year, January first. */
    Set<Month> EVERY_MONTH = Collections.unmodifiableSet(EnumSet.allOf(Month.class));

    /**
     * The runs that start in {@code year}, in the rule's own order: run 1 first. The window keeps that order and
     * those numbers, and sets aside any run that meets one before it.
     */
    @Nonnull
    List<RunDays> runs(int year);

    /** Do the days differ from one year to the next? */
    boolean moves();

    /** Can the rule date more than one run in a year? */
    default boolean several() {
        return false;
    }

    /** Does every year's run start in that year, and does no run of the rule ever meet its next one? */
    boolean valid();

    /** The rule as data, for a log line or an admin answer: the same in every language. */
    @Nonnull
    String describe();

    /**
     * Can a span of {@code before} days ahead of an anchor and {@code after} days past it start in the anchor's
     * own year and stay clear of the next year's span, when the anchor never falls before day
     * {@code earliestAnchorDay} of a common year and two anchors are at least {@code minGapDays} apart?
     */
    private static boolean spanValid(int before, int after, int earliestAnchorDay, int minGapDays) {
        return before >= 0 && after >= 0 && (long) before + after < minGapDays && before < earliestAnchorDay;
    }

    /** {@code months} as an unmodifiable set iterated January first. */
    @Nonnull
    private static Set<Month> inOrder(@Nonnull Set<Month> months) {
        EnumSet<Month> ordered = EnumSet.noneOf(Month.class);
        ordered.addAll(months);
        return Collections.unmodifiableSet(ordered);
    }

    /** {@code " in 03,06"} for some months, nothing for every month. */
    @Nonnull
    private static String inMonths(@Nonnull Set<Month> months) {
        if (months.size() == Month.values().length) {
            return "";
        }
        return " in " + months.stream().map(month -> String.format(Locale.ROOT, "%02d", month.getValue()))
                .collect(Collectors.joining(","));
    }

    /** The most days a run of a rule that comes round every {@code every} units of {@code unitDays} may last. */
    private static int mostDays(int unitDays, int every) {
        return (int) Math.min(REPEATING_MAX_RUN_DAYS, (long) unitDays * Math.max(1, every));
    }

    /** The greatest common divisor of two positive numbers. */
    private static int gcd(int a, int b) {
        return b == 0 ? a : gcd(b, a % b);
    }

    /**
     * How often a repeating rule comes round: every {@code every}th week or month, counted from the week or month
     * {@code anchor} falls in (needed only past 1), and only in {@code months}, by the month a run starts in.
     */
    record Cadence(int every, @Nullable LocalDate anchor, @Nonnull Set<Month> months) {

        /** Every week or month of the year. */
        public static final Cadence ALWAYS = new Cadence(1, null, EVERY_MONTH);

        public Cadence {
            months = inOrder(months);
        }

        /** At least once, with an anchor whenever it skips, in at least one month. */
        public boolean valid() {
            return every >= 1 && (every == 1 || anchor != null) && !months.isEmpty();
        }

        /** Does it run in every month, so a run can always last until the next? */
        public boolean allYear() {
            return months.size() == Month.values().length;
        }

        @Nonnull
        String describe() {
            String skips = every > 1 ? String.format(Locale.ROOT, " every %d from %s", every, anchor) : "";
            return skips + inMonths(months);
        }
    }

    /**
     * How long each run of a repeating rule lasts: {@code days} whole days from {@code at} on its first day
     * (midnight when unsaid), or {@code length} from then, which wins over days, or, with {@code untilNext},
     * until the next run starts, which wins over both.
     */
    record RunLength(int days, @Nullable LocalTime at, @Nullable Duration length, boolean untilNext) {

        /** {@code days} whole days from midnight: what every run was before times of day. */
        @Nonnull
        public static RunLength ofDays(int days) {
            return new RunLength(days, null, null, false);
        }

        /** When a run whose first day is {@code day} starts. */
        @Nonnull
        public LocalDateTime startOn(@Nonnull LocalDate day) {
            return day.atTime(at == null ? LocalTime.MIDNIGHT : at);
        }

        /** The run starting on {@code first}; {@code nextStart} (the next run's start) is read only when it lasts until then. */
        @Nonnull
        public RunDays from(@Nonnull LocalDate first, @Nullable LocalDateTime nextStart) {
            LocalDateTime start = startOn(first);
            if (untilNext && nextStart != null) {
                return new RunDays(start, nextStart);
            }
            if (length != null) {
                return new RunDays(start, start.plus(length));
            }
            return new RunDays(start, startOn(first.plusDays(Math.max(1, days))));
        }

        /** Does a run last at least a moment and at most {@code maxDays} days? A run until the next is judged by its rule. */
        public boolean valid(int maxDays) {
            if (untilNext) {
                return true;
            }
            if (length != null) {
                return !length.isNegative() && !length.isZero() && length.compareTo(Duration.ofDays(maxDays)) <= 0;
            }
            return days >= 1 && days <= maxDays;
        }

        @Nonnull
        String describe() {
            String from = at == null ? "" : " at " + at;
            if (untilNext) {
                return from + " until next";
            }
            if (length != null) {
                return from + " for " + length;
            }
            return String.format(Locale.ROOT, "%s x%d", from, days);
        }
    }

    /** The same month-days every year. A last day before the first runs into the next year. */
    record Fixed(@Nonnull MonthDay first, @Nonnull MonthDay last) implements YearRule {

        /** February 29th, which falls back to the 28th in a year without one. */
        static final MonthDay LEAP_DAY = MonthDay.of(2, 29);

        /** The days of the run that starts in {@code year}. */
        @Nonnull
        public RunDays days(int year) {
            return new RunDays(first.atYear(year), last.atYear(crossesNewYear() ? year + 1 : year));
        }

        @Override
        @Nonnull
        public List<RunDays> runs(int year) {
            return List.of(days(year));
        }

        /** Does the last day come before the first, so each run ends in the next year? */
        public boolean crossesNewYear() {
            return last.isBefore(first);
        }

        @Override
        public boolean moves() {
            return false;
        }

        /**
         * Always, but for February 29th through the next February 28th: in a year without a 29th the next run
         * would start on the 28th, the very day this one ends.
         */
        @Override
        public boolean valid() {
            return !(first.equals(LEAP_DAY) && last.equals(MonthDay.of(2, 28)));
        }

        @Override
        @Nonnull
        public String describe() {
            return String.format(Locale.ROOT, "%02d-%02d..%02d-%02d", first.getMonthValue(), first.getDayOfMonth(),
                    last.getMonthValue(), last.getDayOfMonth());
        }
    }

    /**
     * Several runs on the same month-days every year, one per span. A span's number is its place in the list
     * (the first is run 1), whatever its days. Spans that meet are the window's to set aside, the later written.
     */
    record FixedRuns(@Nonnull List<Fixed> spans) implements YearRule {

        public FixedRuns {
            spans = List.copyOf(spans);
        }

        @Override
        @Nonnull
        public List<RunDays> runs(int year) {
            List<RunDays> out = new ArrayList<>();
            for (Fixed span : spans) {
                out.add(span.days(year));
            }
            return List.copyOf(out);
        }

        @Override
        public boolean moves() {
            return false;
        }

        @Override
        public boolean several() {
            return spans.size() > 1;
        }

        /** Every span valid; spans meeting one another do not stop it, since the window sets the later aside. */
        @Override
        public boolean valid() {
            return spans.stream().allMatch(Fixed::valid);
        }

        @Override
        @Nonnull
        public String describe() {
            return spans.isEmpty() ? "no runs" : spans.stream().map(Fixed::describe).collect(Collectors.joining(", "));
        }
    }

    /** From {@code before} days ahead of Easter Sunday through {@code after} days past it. */
    record Easter(int before, int after) implements YearRule {

        /** The earliest Easter Sunday can fall. */
        static final MonthDay EARLIEST = MonthDay.of(3, 22);

        /** The days of the run that starts in {@code year}. */
        @Nonnull
        public RunDays days(int year) {
            LocalDate sunday = sunday(year);
            return new RunDays(sunday.minusDays(before), sunday.plusDays(after));
        }

        @Override
        @Nonnull
        public List<RunDays> runs(int year) {
            return List.of(days(year));
        }

        @Override
        public boolean moves() {
            return true;
        }

        @Override
        public boolean valid() {
            return spanValid(before, after, EARLIEST.atYear(COMMON_YEAR).getDayOfYear(), EASTER_MIN_GAP_DAYS);
        }

        @Override
        @Nonnull
        public String describe() {
            return String.format(Locale.ROOT, "Easter -%d..+%d", before, after);
        }

        /** Easter Sunday of {@code year}, by the anonymous Gregorian computus (Meeus, Jones and Butcher). */
        @Nonnull
        public static LocalDate sunday(int year) {
            int a = year % 19;
            int b = year / 100;
            int c = year % 100;
            int d = b / 4;
            int e = b % 4;
            int f = (b + 8) / 25;
            int g = (b - f + 1) / 3;
            int h = (19 * a + b - d - g + 15) % 30;
            int i = c / 4;
            int k = c % 4;
            int l = (32 + 2 * e + 2 * i - h - k) % 7;
            int m = (a + 11 * h + 22 * l) / 451;
            int n = h + l - 7 * m + 114;
            return LocalDate.of(year, n / 31, n % 31 + 1);
        }
    }

    /**
     * From {@code before} days ahead of the Nth {@code weekday} of {@code month} through {@code after} days
     * past it. {@code nth} is 1 to 5, or {@link #LAST}; a fifth the month lacks falls back to the fourth, as
     * February 29th falls back to the 28th.
     */
    record Weekday(@Nonnull Month month, @Nonnull DayOfWeek weekday, int nth, int before, int after)
            implements YearRule {

        /** {@code nth} for the last such weekday of the month. */
        public static final int LAST = -1;

        /** The days of the run that starts in {@code year}. */
        @Nonnull
        public RunDays days(int year) {
            LocalDate anchor = anchor(year);
            return new RunDays(anchor.minusDays(before), anchor.plusDays(after));
        }

        @Override
        @Nonnull
        public List<RunDays> runs(int year) {
            return List.of(days(year));
        }

        /** The Nth weekday of the month in {@code year}. */
        @Nonnull
        public LocalDate anchor(int year) {
            return nthOf(YearMonth.of(year, month), weekday, nth);
        }

        /** The Nth {@code weekday} of {@code month}: {@link #LAST} for the last, a fifth it lacks falls back to the fourth. */
        @Nonnull
        static LocalDate nthOf(@Nonnull YearMonth month, @Nonnull DayOfWeek weekday, int nth) {
            if (nth == LAST) {
                return month.atEndOfMonth().with(TemporalAdjusters.previousOrSame(weekday));
            }
            LocalDate counted = month.atDay(1).with(TemporalAdjusters.firstInMonth(weekday)).plusWeeks(nth - 1L);
            return counted.getMonth() == month.getMonth() ? counted : counted.minusWeeks(1);
        }

        @Override
        public boolean moves() {
            return true;
        }

        @Override
        public boolean valid() {
            return (nth == LAST || (nth >= 1 && nth <= 5))
                    && spanValid(before, after, earliestAnchorDay(), WEEKDAY_MIN_GAP_DAYS);
        }

        /**
         * The earliest day of a common year the anchor can fall on: the Nth week's first day, the 22nd for a
         * fifth (its fourth falls there at the earliest when the fifth is missing), the last week's first day.
         */
        private int earliestAnchorDay() {
            int dayOfMonth = nth == LAST ? month.length(false) - 6 : nth == 5 ? 22 : (nth - 1) * 7 + 1;
            return MonthDay.of(month, dayOfMonth).atYear(COMMON_YEAR).getDayOfYear();
        }

        @Override
        @Nonnull
        public String describe() {
            return String.format(Locale.ROOT, "Weekday %s #%d of %02d -%d..+%d", weekday, nth, month.getValue(),
                    before, after);
        }
    }

    /**
     * A run in each month its {@link Cadence} names: from the {@code day} of the month (a day the month lacks
     * falls back to its last), or from the Nth {@code weekday} of it when one is named (a fifth it lacks falls back
     * to the fourth), lasting its {@link RunLength}. {@code day} is unused when {@code weekday} is named. A run's
     * number is its place among the year's months the rule runs in, so a re-date (another day, weekday or length)
     * never renumbers one.
     */
    record Monthly(int day, @Nullable DayOfWeek weekday, int nth, @Nonnull Cadence cadence, @Nonnull RunLength length)
            implements YearRule {

        /** The first day of the run that starts in {@code month}. */
        @Nonnull
        public LocalDate anchor(@Nonnull YearMonth month) {
            return weekday != null ? Weekday.nthOf(month, weekday, nth)
                    : month.atDay(Math.max(1, Math.min(day, month.lengthOfMonth())));
        }

        /** Does a run start in {@code month}: one the cadence names, a whole number of Every months from the anchor's? */
        public boolean runsIn(@Nonnull YearMonth month) {
            if (!cadence.months().contains(month.getMonth())) {
                return false;
            }
            if (cadence.every() <= 1) {
                return true;
            }
            if (cadence.anchor() == null) {
                return false;
            }
            long apart = ChronoUnit.MONTHS.between(YearMonth.from(cadence.anchor()), month);
            return Math.floorMod(apart, cadence.every()) == 0;
        }

        @Override
        @Nonnull
        public List<RunDays> runs(int year) {
            List<RunDays> out = new ArrayList<>();
            for (Month name : Month.values()) {
                YearMonth month = YearMonth.of(year, name);
                if (runsIn(month)) {
                    out.add(length.from(anchor(month), length.untilNext() ? nextStart(month) : null));
                }
            }
            return List.copyOf(out);
        }

        /** The start of the first run after the one in {@code month}: a valid rule has one within a year. */
        @Nullable
        private LocalDateTime nextStart(@Nonnull YearMonth month) {
            YearMonth next = month.plusMonths(1);
            for (int i = 0; i < 12 * Math.max(1, cadence.every()); i++, next = next.plusMonths(1)) {
                if (runsIn(next)) {
                    return length.startOn(anchor(next));
                }
            }
            return null;
        }

        /** Does a month the cadence names ever fall a whole number of Every months from the anchor's? */
        private boolean reaches() {
            if (cadence.every() <= 1 || cadence.anchor() == null) {
                return true;
            }
            int step = gcd(cadence.every(), 12);
            int from = cadence.anchor().getMonthValue();
            for (Month month : cadence.months()) {
                if (Math.floorMod(month.getValue() - from, step) == 0) {
                    return true;
                }
            }
            return false;
        }

        /** A weekday's dates move from year to year, and so do months counted Every few from an anchor. */
        @Override
        public boolean moves() {
            return weekday != null || cadence.every() > 1;
        }

        @Override
        public boolean several() {
            return cadence.months().size() > 1 && cadence.every() < 12;
        }

        @Override
        public boolean valid() {
            boolean anchored = weekday != null ? nth == Weekday.LAST || (nth >= 1 && nth <= 5) : day >= 1 && day <= 31;
            if (!anchored || !cadence.valid() || !reaches()) {
                return false;
            }
            if (length.untilNext()) {
                return cadence.allYear() && cadence.every() <= MONTHLY_MAX_EVERY_UNTIL_NEXT;
            }
            return length.valid(mostDays(MONTHLY_MAX_DAYS, cadence.every()));
        }

        @Override
        @Nonnull
        public String describe() {
            String on = weekday != null ? String.format(Locale.ROOT, "%s #%d", weekday, nth)
                    : String.format(Locale.ROOT, "day %d", day);
            return "Monthly " + on + length.describe() + cadence.describe();
        }
    }

    /**
     * A run on {@code weekday} in each week its {@link Cadence} names, lasting its {@link RunLength}. A run's
     * number is its place among the year's weeks the rule runs in.
     */
    record Weekly(@Nonnull DayOfWeek weekday, @Nonnull Cadence cadence, @Nonnull RunLength length) implements YearRule {

        /**
         * Does a run start on {@code day}, one of this rule's weekdays: in a month the cadence names, a whole number
         * of Every weeks from the anchor's?
         */
        public boolean runsOn(@Nonnull LocalDate day) {
            if (!cadence.months().contains(day.getMonth())) {
                return false;
            }
            if (cadence.every() <= 1) {
                return true;
            }
            if (cadence.anchor() == null) {
                return false;
            }
            LocalDate reference = cadence.anchor().with(TemporalAdjusters.nextOrSame(weekday));
            return Math.floorMod(ChronoUnit.DAYS.between(reference, day) / 7, cadence.every()) == 0;
        }

        @Override
        @Nonnull
        public List<RunDays> runs(int year) {
            List<RunDays> out = new ArrayList<>();
            for (LocalDate day = LocalDate.of(year, 1, 1).with(TemporalAdjusters.nextOrSame(weekday));
                    day.getYear() == year; day = day.plusWeeks(1)) {
                if (runsOn(day)) {
                    out.add(length.from(day, length.untilNext() ? nextStart(day) : null));
                }
            }
            return List.copyOf(out);
        }

        /** The start of the first run after the one on {@code day}: a valid rule has one within a year. */
        @Nullable
        private LocalDateTime nextStart(@Nonnull LocalDate day) {
            LocalDate next = day.plusWeeks(1);
            for (int i = 0; i < 53 * Math.max(1, cadence.every()); i++, next = next.plusWeeks(1)) {
                if (runsOn(next)) {
                    return length.startOn(next);
                }
            }
            return null;
        }

        @Override
        public boolean moves() {
            return true;
        }

        @Override
        public boolean several() {
            return true;
        }

        @Override
        public boolean valid() {
            if (!cadence.valid()) {
                return false;
            }
            if (length.untilNext()) {
                return cadence.allYear() && cadence.every() <= WEEKLY_MAX_EVERY_UNTIL_NEXT;
            }
            return length.valid(mostDays(WEEKLY_MAX_DAYS, cadence.every()));
        }

        @Override
        @Nonnull
        public String describe() {
            return "Weekly " + weekday + length.describe() + cadence.describe();
        }
    }
}
