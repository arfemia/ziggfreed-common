package com.ziggfreed.common.almanac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.ZoneId;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.almanac.AlmanacCalendar.Dates;
import com.ziggfreed.common.almanac.AlmanacCalendar.SeasonState;
import com.ziggfreed.common.occurrence.Occurrence;
import com.ziggfreed.common.occurrence.OccurrenceSource;
import com.ziggfreed.common.occurrence.Occurrences;

/**
 * The calendar as the Almanac reads it: zc-core's occurrence slot, with silence as absence. A season the
 * calendar does not answer for is absent, one it stopped is between seasons, and a forced run is live in
 * the year it is filed under.
 */
class OccurrenceAlmanacCalendarTest {

    private static final long NOW = FixedCalendar.noon("2026-10-07");
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    @AfterEach
    void reset() {
        Occurrences.resetForTests();
    }

    /** A source that knows one event, enabled or not, with the answers a test gives it. */
    private record Source(boolean enabled, @Nullable Occurrence live, @Nullable Occurrence next,
                          @Nonnull List<Occurrence> history, @Nullable Integer first, @Nonnull ZoneId clock)
            implements OccurrenceSource {

        @Override
        public boolean isEnabled(@Nonnull String eventId) {
            return enabled && FixedCalendar.TEST_SEASON.equals(eventId);
        }

        @Override
        @Nullable
        public Occurrence live(@Nonnull String eventId, long nowMs) {
            return isEnabled(eventId) ? live : null;
        }

        @Override
        @Nonnull
        public List<Occurrence> history(@Nonnull String eventId, long nowMs) {
            return isEnabled(eventId) ? history : List.of();
        }

        @Override
        @Nullable
        public Integer firstYear(@Nonnull String eventId) {
            return first;
        }

        @Override
        @Nullable
        public Occurrence next(@Nonnull String eventId, long nowMs) {
            return isEnabled(eventId) ? next : null;
        }

        @Override
        @Nonnull
        public ZoneId zone(@Nonnull String eventId) {
            return clock;
        }
    }

    private static OccurrenceAlmanacCalendar calendar(@Nonnull OccurrenceSource source) {
        return new OccurrenceAlmanacCalendar(() -> source, () -> NOW);
    }

    @Test
    void withNoCalendarFilledThereIsNoSeason() {
        OccurrenceAlmanacCalendar calendar = calendar(OccurrenceSource.NONE);

        assertNull(calendar.state(FixedCalendar.TEST_SEASON));
        assertSame(Dates.UNKNOWN, calendar.dates(FixedCalendar.TEST_SEASON, NOW));
    }

    @Test
    void aLiveRunReadsTheYearItIsFiledUnder() {
        Source source = new Source(true, FixedCalendar.autumn(2026), FixedCalendar.autumn(2027),
                FixedCalendar.history(2026, 2026), 2026, FixedCalendar.UTC);

        assertEquals(SeasonState.liveIn(2026), calendar(source).state(FixedCalendar.TEST_SEASON));
        assertNull(calendar(source).state("other_season"), "an event the calendar does not answer for is absent");
    }

    @Test
    void aStoppedEventStaysListedBetweenSeasons() {
        Source stopped = new Source(true, null, FixedCalendar.autumn(2027), FixedCalendar.history(2026, 2026),
                2026, FixedCalendar.UTC);

        assertEquals(SeasonState.BETWEEN, calendar(stopped).state(FixedCalendar.TEST_SEASON));
    }

    @Test
    void anEventSwitchedOffIsAbsentWhateverItsRunsSay() {
        Source off = new Source(false, FixedCalendar.autumn(2026), FixedCalendar.autumn(2027),
                FixedCalendar.history(2026, 2026), 2026, FixedCalendar.UTC);

        assertNull(calendar(off).state(FixedCalendar.TEST_SEASON));
        assertSame(Dates.UNKNOWN, calendar(off).dates(FixedCalendar.TEST_SEASON, NOW),
                "an absent season has no dates to show");
    }

    @Test
    void aRunForcedOnOutsideItsDatesIsLiveInItsYear() {
        Source forced = new Source(true, FixedCalendar.autumn(2026), FixedCalendar.autumn(2027),
                FixedCalendar.history(2026, 2026), 2026, FixedCalendar.UTC);
        OccurrenceAlmanacCalendar june = new OccurrenceAlmanacCalendar(() -> forced,
                () -> FixedCalendar.noon("2026-06-01"));

        assertEquals(SeasonState.liveIn(2026), june.state(FixedCalendar.TEST_SEASON),
                "a forced run keeps its window's dates and its year");
    }

    @Test
    void theDatesCarryTheRunsTheFirstYearAndTheEventsOwnClock() {
        Occurrence live = FixedCalendar.run(FixedCalendar.TEST_SEASON, 2026, "2026-10-01", "2026-11-03", BERLIN);
        Occurrence next = FixedCalendar.run(FixedCalendar.TEST_SEASON, 2027, "2027-10-01", "2027-11-03", BERLIN);
        Source source = new Source(true, live, next, List.of(live), 2026, BERLIN);

        Dates dates = calendar(source).dates(FixedCalendar.TEST_SEASON, NOW);

        assertEquals(live, dates.live());
        assertEquals(next, dates.next());
        assertEquals(List.of(live), dates.history());
        assertEquals(2026, dates.firstYear());
        assertEquals(BERLIN, dates.zone(), "days are counted in the event's own clock");
    }

    @Test
    void aThrowingSourceIsNoAnswer() {
        OccurrenceSource broken = new OccurrenceSource() {
            @Override
            public boolean isEnabled(@Nonnull String eventId) {
                throw new IllegalStateException("boom");
            }

            @Override
            @Nullable
            public Occurrence live(@Nonnull String eventId, long nowMs) {
                throw new IllegalStateException("boom");
            }

            @Override
            @Nonnull
            public List<Occurrence> history(@Nonnull String eventId, long nowMs) {
                throw new IllegalStateException("boom");
            }
        };

        assertNull(calendar(broken).state(FixedCalendar.TEST_SEASON));
        assertSame(Dates.UNKNOWN, calendar(broken).dates(FixedCalendar.TEST_SEASON, NOW));
    }

    @Test
    void theProductionCalendarReadsWhateverFilledTheSlotAtTheMomentOfAsking() {
        assertNull(OccurrenceAlmanacCalendar.INSTANCE.state(FixedCalendar.TEST_SEASON), "unfilled: absent");

        Occurrences.fill(new Source(true, null, null, List.of(), 2026, FixedCalendar.UTC));

        assertEquals(SeasonState.BETWEEN, OccurrenceAlmanacCalendar.INSTANCE.state(FixedCalendar.TEST_SEASON),
                "a fill after the calendar was built is read on the next ask");
    }

    @Test
    void aCalendarThatKnowsNoDatesAnswersUnknown() {
        AlmanacCalendar lambda = eventId -> SeasonState.BETWEEN;

        assertSame(Dates.UNKNOWN, lambda.dates(FixedCalendar.TEST_SEASON, NOW),
                "a one-method calendar (a test's lambda) knows no dates");
    }
}
