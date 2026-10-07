package com.ziggfreed.common.calendar;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.MonthDay;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;

import javax.annotation.Nonnull;

/**
 * How one year's run is dated: the same month-days every year, a span around Easter Sunday, or a span
 * around the Nth weekday of a month. Pure: no clock, no zone, no store.
 *
 * <p>A rule is {@linkplain #valid valid} only when EVERY year's run starts in that year and lasts 1 to
 * {@value #MAX_RUN_DAYS} days, both ends in. That is what lets a run's first instant name its year, the
 * fact every reader of the calendar keys a run by.
 */
public sealed interface YearRule permits YearRule.Fixed, YearRule.Easter, YearRule.Weekday {

    /** The most days one run may last, both ends in. */
    int MAX_RUN_DAYS = 366;

    /** A year with no February 29th: there a day-of-year count runs lowest for every day after February. */
    int COMMON_YEAR = 2001;

    /** The days of the run that starts in {@code year}. */
    @Nonnull
    RunDays days(int year);

    /** Do the days differ from one year to the next? */
    boolean moves();

    /** Does every year's run start in that year and last 1 to {@value #MAX_RUN_DAYS} days? */
    boolean valid();

    /** The rule as data, for a log line or an admin answer: the same in every language. */
    @Nonnull
    String describe();

    /**
     * Can a span of {@code before} days ahead of an anchor and {@code after} days past it start in the
     * anchor's own year and last at most {@value #MAX_RUN_DAYS} days, when the anchor never falls before
     * day {@code earliestAnchorDay} of a common year?
     */
    private static boolean spanValid(int before, int after, int earliestAnchorDay) {
        return before >= 0 && after >= 0 && (long) before + after < MAX_RUN_DAYS && before < earliestAnchorDay;
    }

    /** The same month-days every year. A last day before the first runs into the next year. */
    record Fixed(@Nonnull MonthDay first, @Nonnull MonthDay last) implements YearRule {

        @Override
        @Nonnull
        public RunDays days(int year) {
            return new RunDays(first.atYear(year), last.atYear(crossesNewYear() ? year + 1 : year));
        }

        /** Does the last day come before the first, so each run ends in the next year? */
        public boolean crossesNewYear() {
            return last.isBefore(first);
        }

        @Override
        public boolean moves() {
            return false;
        }

        /** Always: month-days start in their own year, and two of them are at most 366 days apart. */
        @Override
        public boolean valid() {
            return true;
        }

        @Override
        @Nonnull
        public String describe() {
            return String.format(Locale.ROOT, "%02d-%02d..%02d-%02d", first.getMonthValue(), first.getDayOfMonth(),
                    last.getMonthValue(), last.getDayOfMonth());
        }
    }

    /** From {@code before} days ahead of Easter Sunday through {@code after} days past it. */
    record Easter(int before, int after) implements YearRule {

        /** The earliest Easter Sunday can fall. */
        static final MonthDay EARLIEST = MonthDay.of(3, 22);

        @Override
        @Nonnull
        public RunDays days(int year) {
            LocalDate sunday = sunday(year);
            return new RunDays(sunday.minusDays(before), sunday.plusDays(after));
        }

        @Override
        public boolean moves() {
            return true;
        }

        @Override
        public boolean valid() {
            return spanValid(before, after, EARLIEST.atYear(COMMON_YEAR).getDayOfYear());
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

        @Override
        @Nonnull
        public RunDays days(int year) {
            LocalDate anchor = anchor(year);
            return new RunDays(anchor.minusDays(before), anchor.plusDays(after));
        }

        /** The Nth weekday of the month in {@code year}. */
        @Nonnull
        public LocalDate anchor(int year) {
            YearMonth ym = YearMonth.of(year, month);
            if (nth == LAST) {
                return ym.atEndOfMonth().with(TemporalAdjusters.previousOrSame(weekday));
            }
            LocalDate counted = ym.atDay(1).with(TemporalAdjusters.firstInMonth(weekday)).plusWeeks(nth - 1L);
            return counted.getMonth() == month ? counted : counted.minusWeeks(1);
        }

        @Override
        public boolean moves() {
            return true;
        }

        @Override
        public boolean valid() {
            return (nth == LAST || (nth >= 1 && nth <= 5)) && spanValid(before, after, earliestAnchorDay());
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
}
