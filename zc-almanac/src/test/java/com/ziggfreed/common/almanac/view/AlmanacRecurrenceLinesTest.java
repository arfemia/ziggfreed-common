package com.ziggfreed.common.almanac.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.Month;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.protocol.IntParamValue;
import com.hypixel.hytale.protocol.LongParamValue;
import com.hypixel.hytale.protocol.ParamValue;
import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.almanac.AlmanacCalendar.Dates;
import com.ziggfreed.common.almanac.AlmanacText;
import com.ziggfreed.common.almanac.FixedCalendar;
import com.ziggfreed.common.almanac.OccurrenceAlmanacCalendar;
import com.ziggfreed.common.almanac.view.AlmanacView.Season;
import com.ziggfreed.common.almanac.view.AlmanacView.Timing;
import com.ziggfreed.common.occurrence.Occurrence;
import com.ziggfreed.common.occurrence.OccurrenceSource;
import com.ziggfreed.common.occurrence.Occurrences;
import com.ziggfreed.common.occurrence.Recurrence;

/**
 * The dates line of an event that comes round monthly or weekly: it says how it recurs (the day or the Nth weekday,
 * every N, the months, how long a run lasts) in place of one run's year and days, then gives the next run's days
 * ("Next: Oct 4 to Oct 10", or its hours). An event that comes round once a year reads exactly as it did. Each line
 * is read back in English from the shipped en-US file, so a test shows the sentence a player sees, and every key it
 * reads is one the module speaks.
 */
class AlmanacRecurrenceLinesTest {

    private static final ZoneId UTC = FixedCalendar.UTC;

    @AfterEach
    void reset() {
        Occurrences.resetForTests();
    }

    // ---- the examples ----

    @Test
    void theTravelingFairSaysItsFirstSundayAndItsNextWeek() {
        Recurrence fair = Recurrence.Monthly.onWeekday(1, DayOfWeek.SUNDAY, 1, Set.of(), Recurrence.Length.days(7));
        Occurrence october = days("traveling_fair", 2026, 10, "2026-10-04", "2026-10-10");
        Occurrence november = days("traveling_fair", 2026, 11, "2026-11-01", "2026-11-07");
        Occurrence september = days("traveling_fair", 2026, 9, "2026-09-06", "2026-09-12");

        Timing between = timing("traveling_fair", new Dates(null, october, List.of(september), 2026, UTC, true, fair),
                "2026-09-30");
        Message line = AlmanacLines.window(between);
        assertNotNull(line);
        assertKey("recur.with_next", line);
        assertEquals("The first Sunday of every month, for 7 days. Next: Oct 4 to Oct 10", english(line));

        Timing live = timing("traveling_fair", new Dates(october, november, List.of(september, october), 2026, UTC,
                true, fair), "2026-10-06");
        assertEquals("The first Sunday of every month, for 7 days. Next: Nov 1 to Nov 7",
                english(AlmanacLines.window(live)), "while a run is on, the next is the one after it");
    }

    @Test
    void aQuarterlyMarketNamesItsMonths() {
        Recurrence market = Recurrence.Monthly.onDay(15, 1,
                Set.of(Month.MARCH, Month.JUNE, Month.SEPTEMBER, Month.DECEMBER), Recurrence.Length.days(2));
        Occurrence december = days("market", 2026, 12, "2026-12-15", "2026-12-16");
        assertEquals("On the 15th of March, June, September and December, for 2 days. Next: Dec 15 to Dec 16",
                english(AlmanacLines.window(timing("market", between(december, market), "2026-10-07"))));
        assertEquals("On the 1st of June and July, for 2 days", english(AlmanacLines.recurrence(
                Recurrence.Monthly.onDay(1, 1, Set.of(Month.JULY, Month.JUNE), Recurrence.Length.days(2)))));
    }

    @Test
    void aMonthlyRuleEveryOtherMonthOrEveryFewMonthsSaysHowOften() {
        Occurrence november = days("market", 2026, 11, "2026-11-01", "2026-11-07");
        assertEquals("On the 1st of every other month, for 7 days. Next: Nov 1 to Nov 7",
                english(AlmanacLines.window(timing("market", between(november,
                        Recurrence.Monthly.onDay(1, 2, Set.of(), Recurrence.Length.days(7))), "2026-10-07"))));
        assertEquals("On the 1st of every 3 months, for 7 days", english(AlmanacLines.recurrence(
                Recurrence.Monthly.onDay(1, 3, Set.of(), Recurrence.Length.days(7)))));
        assertEquals("On the 1st of every other month in March, May and July, for 7 days",
                english(AlmanacLines.recurrence(Recurrence.Monthly.onDay(1, 2,
                        Set.of(Month.MARCH, Month.MAY, Month.JULY), Recurrence.Length.days(7)))));
    }

    @Test
    void theFishingContestShowsItsClockTimesAndItsNextSunday() {
        Recurrence contest = new Recurrence.Weekly(DayOfWeek.SUNDAY, 1, Set.of(),
                Recurrence.Length.timed(LocalTime.of(14, 0), Duration.ofHours(2)));
        Occurrence sunday = timed("fishing_contest", 2026, 41, "2026-10-11T14:00:00Z", "2026-10-11T16:00:00Z");
        Message line = AlmanacLines.window(timing("fishing_contest", between(sunday, contest), "2026-10-07"));
        assertEquals("Every Sunday, 14:00 to 16:00. Next: Sunday Oct 11, 14:00 to 16:00", english(line));
        FormattedMessage next = line.getFormattedMessage().messageParams.get("1");
        assertKey("next.hours", next);
        assertEquals("14:00", next.messageParams.get("3").rawText, "a clock time is text, the same in every locale");
    }

    @Test
    void aWeeklyRuleADayLongOrEveryOtherWeek() {
        Occurrence saturday = days("market_day", 2026, 41, "2026-10-10", "2026-10-10");
        assertEquals("Every Saturday, all day. Next: Oct 10", english(AlmanacLines.window(timing("market_day",
                between(saturday, new Recurrence.Weekly(DayOfWeek.SATURDAY, 1, Set.of(), Recurrence.Length.days(1))),
                "2026-10-07"))), "a one-day run names its day once");

        Occurrence monday = days("tournament", 2026, 42, "2026-10-12", "2026-10-18");
        assertEquals("Every other Monday, for 7 days. Next: Oct 12 to Oct 18", english(AlmanacLines.window(
                timing("tournament", between(monday,
                        new Recurrence.Weekly(DayOfWeek.MONDAY, 2, Set.of(), Recurrence.Length.days(7))),
                        "2026-10-07"))));
        assertEquals("Every 3 weeks on Monday in June and July, for 7 days", english(AlmanacLines.recurrence(
                new Recurrence.Weekly(DayOfWeek.MONDAY, 3, Set.of(Month.JUNE, Month.JULY),
                        Recurrence.Length.days(7)))));
        assertEquals("Every Sunday in December, all day", english(AlmanacLines.recurrence(
                new Recurrence.Weekly(DayOfWeek.SUNDAY, 1, Set.of(Month.DECEMBER), Recurrence.Length.days(1)))));
    }

    @Test
    void aRunUntilTheNextSaysSoAndItsNextRunNamesItsDays() {
        Occurrence november = days("guild_month", 2026, 11, "2026-11-01", "2026-11-30");
        assertEquals("On the 1st of every month, until the next. Next: Nov 1 to Nov 30",
                english(AlmanacLines.window(timing("guild_month", between(november,
                        Recurrence.Monthly.onDay(1, 1, Set.of(), Recurrence.Length.untilNext(null))), "2026-10-07"))));
    }

    // ---- every other shape a rule can take ----

    @Test
    void theOtherLengthsAndTheLastWeekdayOfTheMonth() {
        assertEquals("The last Friday of every month, all day", english(AlmanacLines.recurrence(
                Recurrence.Monthly.onWeekday(Recurrence.LAST, DayOfWeek.FRIDAY, 1, Set.of(),
                        Recurrence.Length.days(1)))));
        assertEquals("The fifth Wednesday of every month, for 2 days", english(AlmanacLines.recurrence(
                Recurrence.Monthly.onWeekday(5, DayOfWeek.WEDNESDAY, 1, Set.of(), Recurrence.Length.days(2)))));
        assertEquals("Every Friday, from 18:00, for 2 days", english(AlmanacLines.recurrence(
                new Recurrence.Weekly(DayOfWeek.FRIDAY, 1, Set.of(), Recurrence.Length.days(2, LocalTime.of(18, 0))))));
        assertEquals("Every Friday, from 18:00, for 1 day", english(AlmanacLines.recurrence(
                new Recurrence.Weekly(DayOfWeek.FRIDAY, 1, Set.of(), Recurrence.Length.days(1, LocalTime.of(18, 0))))),
                "one, one space, brace: the plural option the client reads");
        assertEquals("On the 1st of every month, from 18:00, until the next", english(AlmanacLines.recurrence(
                Recurrence.Monthly.onDay(1, 1, Set.of(), Recurrence.Length.untilNext(LocalTime.of(18, 0))))));
        assertEquals("Every Saturday, 22:00 to 02:00", english(AlmanacLines.recurrence(
                new Recurrence.Weekly(DayOfWeek.SATURDAY, 1, Set.of(),
                        Recurrence.Length.timed(LocalTime.of(22, 0), Duration.ofHours(4))))),
                "hours past midnight still read as clock times");
        assertEquals("Every Thursday, 00:00 to 06:30", english(AlmanacLines.recurrence(
                new Recurrence.Weekly(DayOfWeek.THURSDAY, 1, Set.of(),
                        Recurrence.Length.timed(null, Duration.ofMinutes(390))))));
        assertEquals("Every Monday, from 06:00, for 2 days", english(AlmanacLines.recurrence(
                new Recurrence.Weekly(DayOfWeek.MONDAY, 1, Set.of(),
                        Recurrence.Length.timed(LocalTime.of(6, 0), Duration.ofHours(36))))),
                "a length of a day or more is said in the days it reaches into");
        assertEquals("Every Monday, for 2 days", english(AlmanacLines.recurrence(
                new Recurrence.Weekly(DayOfWeek.MONDAY, 1, Set.of(), Recurrence.Length.timed(null,
                        Duration.ofHours(48))))));
        assertEquals("On the 31st of every month, all day", english(AlmanacLines.recurrence(
                Recurrence.Monthly.onDay(31, 1, Set.of(), Recurrence.Length.days(1)))));
    }

    @Test
    void aNextRunWithATimeThatLastsDaysNamesBothEnds() {
        Occurrence weekend = timed("weekend", 2026, 41, "2026-10-09T18:00:00Z", "2026-10-11T18:00:00Z");
        Recurrence weekends = new Recurrence.Weekly(DayOfWeek.FRIDAY, 1, Set.of(),
                Recurrence.Length.days(2, LocalTime.of(18, 0)));
        assertEquals("Every Friday, from 18:00, for 2 days. Next: Oct 9, 18:00 to Oct 11, 18:00",
                english(AlmanacLines.window(timing("weekend", between(weekend, weekends), "2026-10-07"))));

        Occurrence night = timed("night_market", 2026, 41, "2026-10-10T22:00:00Z", "2026-10-11T02:00:00Z");
        Recurrence nights = new Recurrence.Weekly(DayOfWeek.SATURDAY, 1, Set.of(),
                Recurrence.Length.timed(LocalTime.of(22, 0), Duration.ofHours(4)));
        assertEquals("Every Saturday, 22:00 to 02:00. Next: Saturday Oct 10, 22:00 to 02:00",
                english(AlmanacLines.window(timing("night_market", between(night, nights), "2026-10-07"))));
    }

    @Test
    void theNextRunIsCountedInTheEventsOwnClock() {
        ZoneId tokyo = ZoneId.of("Asia/Tokyo");
        Recurrence contest = new Recurrence.Weekly(DayOfWeek.SUNDAY, 1, Set.of(),
                Recurrence.Length.timed(LocalTime.of(14, 0), Duration.ofHours(2)));
        Occurrence sunday = timed("fishing_contest", 2026, 41, "2026-10-11T05:00:00Z", "2026-10-11T07:00:00Z");
        Dates dates = new Dates(null, sunday, List.of(), 2026, tokyo, true, contest);
        assertEquals("Every Sunday, 14:00 to 16:00. Next: Sunday Oct 11, 14:00 to 16:00",
                english(AlmanacLines.window(timing("fishing_contest", dates, "2026-10-07"))));
    }

    @Test
    void withNoNextRunTheLineSaysTheRecurrenceAlone() {
        Recurrence fair = Recurrence.Monthly.onWeekday(1, DayOfWeek.SUNDAY, 1, Set.of(), Recurrence.Length.days(7));
        Occurrence september = days("traveling_fair", 2026, 9, "2026-09-06", "2026-09-12");
        Timing timing = timing("traveling_fair", new Dates(null, null, List.of(september), 2026, UTC, true, fair),
                "2026-09-30");
        Message line = AlmanacLines.window(timing);
        assertKey("recur", line);
        assertEquals("The first Sunday of every month, for 7 days", english(line));
    }

    // ---- once a year, unchanged ----

    @Test
    void aOnceAYearEventsLinesAreUnchanged() {
        Occurrence run = FixedCalendar.autumn(2026);
        Occurrence next = FixedCalendar.autumn(2027);
        Season season = new Season(FixedCalendar.TEST_SEASON, null, null, null, true, 2026);
        long now = FixedCalendar.noon("2026-10-07");

        Timing every = AlmanacView.timing(season, new Dates(run, next, List.of(run), 2026, UTC, false, null), now);
        assertEquals(AlmanacView.timing(season, new Dates(run, next, List.of(run), 2026, UTC), now), every,
                "no recurrence, the same timing as before");
        assertNull(every.recurring());
        Message window = AlmanacLines.window(every);
        assertKey("window", window);
        assertEquals("Every year, October 1 to November 3", english(window));

        Timing moving = AlmanacView.timing(season, new Dates(run, next, List.of(run), 2026, UTC, true, null), now);
        assertEquals(AlmanacView.timing(season, new Dates(run, next, List.of(run), 2026, UTC, true), now), moving);
        assertEquals("In 2026, October 1 to November 3", english(AlmanacLines.window(moving)));

        Occurrence day = FixedCalendar.run("anniversary", 2027, "2027-01-13", "2027-01-13", UTC);
        Timing oneDay = AlmanacView.timing(new Season("anniversary", null, null, null, true, 2027),
                new Dates(day, null, List.of(day), 2027, UTC), FixedCalendar.noon("2027-01-13"));
        assertEquals("Every year, January 13", english(AlmanacLines.window(oneDay)), "the one-day line is untouched");
    }

    // ---- the calendar's answer reaches the page ----

    @Test
    void theOccurrenceSlotsRecurrenceReachesTheDatesItIsAskedAt() {
        Recurrence fair = Recurrence.Monthly.onWeekday(1, DayOfWeek.SUNDAY, 1, Set.of(), Recurrence.Length.days(7));
        long now = FixedCalendar.noon("2026-09-30");
        Map<String, Long> askedAt = new HashMap<>();
        OccurrenceSource source = new OccurrenceSource() {
            @Override
            public boolean isEnabled(@Nonnull String eventId) {
                return !"switched_off".equals(eventId);
            }

            @Override
            @Nullable
            public Occurrence live(@Nonnull String eventId, long nowMs) {
                return null;
            }

            @Override
            @Nonnull
            public List<Occurrence> history(@Nonnull String eventId, long nowMs) {
                return List.of();
            }

            @Override
            @Nullable
            public Recurrence recurrence(@Nonnull String eventId, long nowMs) {
                askedAt.put(eventId, nowMs);
                return "traveling_fair".equals(eventId) ? fair : null;
            }
        };
        OccurrenceAlmanacCalendar calendar = new OccurrenceAlmanacCalendar(() -> source, () -> now);

        assertEquals(fair, calendar.dates("traveling_fair", now).recurrence());
        assertEquals(Long.valueOf(now), askedAt.get("traveling_fair"), "asked at the page's own moment");
        assertNull(calendar.dates("hallows_eve", now).recurrence(), "a once-a-year event has none");
        assertNull(calendar.dates("switched_off", now).recurrence(), "an absent season knows nothing");
        assertNull(Dates.UNKNOWN.recurrence());
    }

    // ---- the keys ----

    @Test
    void everyKeyTheseLinesReadIsSpokenAndWrittenWithTheSpacedPluralForm() {
        Set<String> read = new TreeSet<>();
        Recurrence[] rules = {
                Recurrence.Monthly.onWeekday(Recurrence.LAST, DayOfWeek.MONDAY, 3, Set.of(Month.MAY, Month.JUNE),
                        Recurrence.Length.days(3, LocalTime.of(9, 30))),
                Recurrence.Monthly.onDay(2, 2, Set.of(), Recurrence.Length.untilNext(LocalTime.of(9, 0))),
                new Recurrence.Weekly(DayOfWeek.TUESDAY, 2, Set.of(Month.MAY), Recurrence.Length.days(1))};
        for (Recurrence rule : rules) {
            collect(AlmanacLines.recurrence(rule).getFormattedMessage(), read);
        }
        for (int day = 1; day <= 31; day++) {
            collect(AlmanacLines.ordinalDay(day).getFormattedMessage(), read);
        }
        for (int nth = 1; nth <= 5; nth++) {
            collect(AlmanacLines.nth(nth).getFormattedMessage(), read);
        }
        for (DayOfWeek weekday : DayOfWeek.values()) {
            collect(AlmanacLines.weekday(weekday).getFormattedMessage(), read);
        }
        for (String key : read) {
            assertTrue(AlmanacText.SPOKEN.contains(key), "the module speaks " + key);
        }
        assertTrue(read.containsAll(List.of("ordinal.day.31", "ordinal.nth.5", "ordinal.nth.last", "weekday.7")));
        for (String key : AlmanacText.SPOKEN) {
            String value = english().get(key);
            if (isRecurrenceKey(key)) {
                assertNotNull(value, "en-US ships " + key);
                assertFalse(value.matches(".*\\b(zero|one|two|few|many|other)\\{.*"),
                        "a plural option is keyword, one space, brace: " + key + " = " + value);
                assertFalse(value.indexOf((char) 0x2014) >= 0, "no em-dash: " + key);
            }
        }
    }

    // ---- fixtures ----

    private static boolean isRecurrenceKey(@Nonnull String key) {
        return key.startsWith("recur") || key.startsWith("next") || key.startsWith("list.")
                || key.startsWith("weekday.") || key.startsWith("ordinal.");
    }

    @Nonnull
    private static Timing timing(@Nonnull String eventId, @Nonnull Dates dates, @Nonnull String today) {
        Season season = new Season(eventId, null, null, null, dates.live() != null,
                dates.live() == null ? 0 : dates.live().year());
        FixedCalendar calendar = new FixedCalendar().season(eventId, dates);
        return AlmanacView.timing(season, calendar, FixedCalendar.noon(today, dates.zone()));
    }

    /** A season between runs, its next run {@code next}, with {@code rule}. */
    @Nonnull
    private static Dates between(@Nonnull Occurrence next, @Nonnull Recurrence rule) {
        return new Dates(null, next, List.of(), next.year(), UTC, true, rule);
    }

    @Nonnull
    private static Occurrence days(@Nonnull String eventId, int year, int number, @Nonnull String first,
            @Nonnull String last) {
        Occurrence run = FixedCalendar.run(eventId, year, first, last, UTC);
        return new Occurrence(eventId, year, number, run.startMs(), run.endMs());
    }

    @Nonnull
    private static Occurrence timed(@Nonnull String eventId, int year, int number, @Nonnull String start,
            @Nonnull String end) {
        return new Occurrence(eventId, year, number, Instant.parse(start).toEpochMilli(),
                Instant.parse(end).toEpochMilli());
    }

    private static void assertKey(@Nonnull String key, @Nonnull Message message) {
        assertKey(key, message.getFormattedMessage());
    }

    private static void assertKey(@Nonnull String key, @Nonnull FormattedMessage message) {
        assertEquals(AlmanacText.PREFIX + key, message.messageId);
    }

    private static void collect(@Nonnull FormattedMessage message, @Nonnull Set<String> keys) {
        if (message.messageId != null) {
            keys.add(message.messageId.substring(AlmanacText.PREFIX.length()));
        }
        if (message.messageParams != null) {
            for (FormattedMessage nested : message.messageParams.values()) {
                collect(nested, keys);
            }
        }
    }

    // ---- the English a player reads ----

    private static Map<String, String> english;

    /** The shipped en-US almanac file, key to value. */
    @Nonnull
    private static Map<String, String> english() {
        if (english == null) {
            Path file = Path.of("src", "main", "resources", "Server", "Languages", "en-US",
                    "ziggfreedcommon.almanac.lang");
            Map<String, String> values = new LinkedHashMap<>();
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    int eq = trimmed.indexOf('=');
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#") && eq > 0) {
                        values.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            english = values;
        }
        return english;
    }

    /** {@code message} as the en-US file reads it: each nested key in place, numbers bare, plurals chosen. */
    @Nonnull
    private static String english(@Nullable Message message) {
        assertNotNull(message, "a line to read");
        return render(message.getFormattedMessage());
    }

    @Nonnull
    private static String render(@Nonnull FormattedMessage message) {
        if (message.messageId == null) {
            return message.rawText == null ? "" : message.rawText;
        }
        assertTrue(message.messageId.startsWith(AlmanacText.PREFIX), message.messageId);
        String key = message.messageId.substring(AlmanacText.PREFIX.length());
        String value = english().get(key);
        assertNotNull(value, "en-US ziggfreedcommon.almanac.lang ships no " + key);
        return fill(value, message);
    }

    @Nonnull
    private static String fill(@Nonnull String text, @Nonnull FormattedMessage message) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c != '{') {
                out.append(c);
                i++;
                continue;
            }
            int close = closing(text, i);
            out.append(argument(text.substring(i + 1, close), message));
            i = close + 1;
        }
        return out.toString();
    }

    @Nonnull
    private static String argument(@Nonnull String body, @Nonnull FormattedMessage message) {
        String[] parts = body.split(",", 3);
        String name = parts[0].trim();
        if (parts.length == 1) {
            FormattedMessage nested = message.messageParams == null ? null : message.messageParams.get(name);
            return nested != null ? render(nested) : Long.toString(number(message, name));
        }
        String type = parts[1].trim();
        if (type.equals("number")) {
            return Long.toString(number(message, name));
        }
        if (type.equals("plural") && parts.length == 3) {
            return plural(parts[2].trim(), number(message, name), message);
        }
        return fail("an argument the client would not read: {" + body + "}");
    }

    /** {@code one {...} other {...}}: keyword, ONE space, brace, as the client reads a plural option. */
    @Nonnull
    private static String plural(@Nonnull String options, long count, @Nonnull FormattedMessage message) {
        Map<String, String> bodies = new HashMap<>();
        int i = 0;
        while (i < options.length()) {
            int space = options.indexOf(' ', i);
            assertTrue(space > i, "a plural option is keyword, one space, brace: " + options);
            assertEquals('{', options.charAt(space + 1), "a plural option is keyword, one space, brace: " + options);
            int close = closing(options, space + 1);
            bodies.put(options.substring(i, space), options.substring(space + 2, close));
            i = close + 1;
            while (i < options.length() && options.charAt(i) == ' ') {
                i++;
            }
        }
        String chosen = count == 1 && bodies.containsKey("one") ? bodies.get("one") : bodies.get("other");
        assertNotNull(chosen, "a plural names its other: " + options);
        return fill(chosen, message);
    }

    private static int closing(@Nonnull String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            if (text.charAt(i) == '{') {
                depth++;
            } else if (text.charAt(i) == '}' && --depth == 0) {
                return i;
            }
        }
        return fail("an unclosed brace in " + text);
    }

    private static long number(@Nonnull FormattedMessage message, @Nonnull String name) {
        ParamValue value = message.params == null ? null : message.params.get(name);
        if (value instanceof LongParamValue typed) {
            return typed.value;
        }
        if (value instanceof IntParamValue typed) {
            return typed.value;
        }
        return fail("no number bound to {" + name + "} of " + message.messageId);
    }
}
