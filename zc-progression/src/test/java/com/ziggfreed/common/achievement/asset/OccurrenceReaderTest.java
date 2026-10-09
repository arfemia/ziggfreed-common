package com.ziggfreed.common.achievement.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.occurrence.Occurrence;
import com.ziggfreed.common.occurrence.OccurrenceSource;
import com.ziggfreed.common.occurrence.Occurrences;

/**
 * The three questions a yearly achievement asks, answered from zc-core's occurrence slot: a year is
 * live only while the run going on is that year's, the years look past the owner's switches, every
 * timed question carries the reader's own clock reading, and the production reader finds the slot as
 * it is filled at the moment of asking.
 */
class OccurrenceReaderTest {

    private static final String EVENT = "yourmod_festival";

    @BeforeEach
    @AfterEach
    void emptyTheSlot() {
        Occurrences.resetForTests();
    }

    @Test
    void onlyTheYearOfTheRunGoingOnIsLive() {
        OccurrenceReader reader = new FakeCalendar().event(EVENT, 2024, 2026).live(EVENT, 2026).reader();

        assertTrue(reader.isLive(EVENT, 2026));
        assertFalse(reader.isLive(EVENT, 2025), "a past year's run is over");
        assertFalse(reader.isLive(EVENT, 2027), "and next year's has not begun");
    }

    @Test
    void aSwitchedOffEventKeepsItsYearsButNoYearOfItIsLive() {
        OccurrenceReader reader = new FakeCalendar().event(EVENT, 2024, 2026).live(EVENT, 2026)
                .switchedOff(EVENT).reader();

        assertEquals(Integer.valueOf(2024), reader.firstYear(EVENT), "the years look past the switch");
        assertEquals(Integer.valueOf(2026), reader.currentYear(EVENT));
        assertFalse(reader.isLive(EVENT, 2026), "off means absent: nothing counts toward it");
    }

    @Test
    void anEventNoCalendarKnowsHasNoYearsAndNoLiveYear() {
        OccurrenceReader reader = new FakeCalendar().reader();

        assertNull(reader.firstYear(EVENT));
        assertNull(reader.currentYear(EVENT));
        assertFalse(reader.isLive(EVENT, 2026));
    }

    @Test
    void everyTimedQuestionCarriesTheReadersOwnClock() {
        List<Long> askedAt = new ArrayList<>();
        OccurrenceSource recording = new OccurrenceSource() {
            @Override
            public boolean isEnabled(@Nonnull String eventId) {
                return true;
            }

            @Override
            @Nullable
            public Occurrence live(@Nonnull String eventId, long nowMs) {
                askedAt.add(nowMs);
                return null;
            }

            @Override
            @Nonnull
            public List<Occurrence> history(@Nonnull String eventId, long nowMs) {
                return List.of();
            }

            @Override
            @Nullable
            public Integer currentYear(@Nonnull String eventId, long nowMs) {
                askedAt.add(nowMs);
                return 2026;
            }
        };
        OccurrenceReader reader = new OccurrenceReader(() -> recording, () -> 1_234L);

        reader.isLive(EVENT, 2026);
        reader.currentYear(EVENT);
        assertEquals(List.of(1_234L, 1_234L), askedAt, "the reader's clock, never one of the source's own");
    }

    // Stay per year: every run of a year keeps that year's ONE copy live; a later run never mints a second.
    @Test
    void everyRunOfAYearKeepsThatYearsOneCopyLive() {
        long[] now = {0L};
        OccurrenceSource fairs = new OccurrenceSource() {
            private final Occurrence spring = new Occurrence(EVENT, 2026, 1, 100L, 200L);
            private final Occurrence autumn = new Occurrence(EVENT, 2026, 2, 300L, 400L);

            @Override
            public boolean isEnabled(@Nonnull String eventId) {
                return EVENT.equals(eventId);
            }

            @Override
            @Nullable
            public Occurrence live(@Nonnull String eventId, long nowMs) {
                return spring.contains(nowMs) ? spring : autumn.contains(nowMs) ? autumn : null;
            }

            @Override
            @Nonnull
            public List<Occurrence> history(@Nonnull String eventId, long nowMs) {
                return List.of();
            }

            @Override
            @Nullable
            public Integer firstYear(@Nonnull String eventId) {
                return 2026;
            }

            @Override
            @Nullable
            public Integer currentYear(@Nonnull String eventId, long nowMs) {
                return 2026;
            }
        };
        OccurrenceReader reader = new OccurrenceReader(() -> fairs, () -> now[0]);
        now[0] = 150L;
        assertTrue(reader.isLive(EVENT, 2026), "the spring run is 2026's");
        now[0] = 250L;
        assertFalse(reader.isLive(EVENT, 2026), "between the runs no copy is live");
        now[0] = 350L;
        assertTrue(reader.isLive(EVENT, 2026), "the autumn run is 2026's too: the same copy");
        assertEquals(Integer.valueOf(2026), reader.currentYear(EVENT), "and the year minted for is still 2026");
    }

    @Test
    void theProductionReaderFindsTheSlotAsItIsFilledWhenAsked() {
        OccurrenceReader reader = OccurrenceReader.LIVE;
        assertNull(reader.firstYear(EVENT), "unfilled, no calendar knows the event");

        Occurrences.fill(new FakeCalendar().event(EVENT, 2026, 2026).live(EVENT, 2026));
        assertEquals(Integer.valueOf(2026), reader.firstYear(EVENT), "filled later, read afresh");
        assertTrue(reader.isLive(EVENT, 2026));
    }
}
