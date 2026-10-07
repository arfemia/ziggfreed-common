package com.ziggfreed.common.occurrence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The one door to recurring events: unfilled it answers that every event is absent, and filled it
 * answers through whatever filled it.
 */
class OccurrencesTest {

    private static final Occurrence RUN = new Occurrence("Spring_Fair", 2026, 1_000L, 2_000L);

    /** A source that knows one event with one run. */
    private static final OccurrenceSource ONE_RUN = new OccurrenceSource() {
        @Override
        public boolean isEnabled(@Nonnull String eventId) {
            return "spring_fair".equalsIgnoreCase(eventId);
        }

        @Override
        @Nullable
        public Occurrence live(@Nonnull String eventId, long nowMs) {
            return isEnabled(eventId) && RUN.contains(nowMs) ? RUN : null;
        }

        @Override
        @Nonnull
        public List<Occurrence> history(@Nonnull String eventId, long nowMs) {
            return isEnabled(eventId) && nowMs >= RUN.startMs() ? List.of(RUN) : List.of();
        }
    };

    /**
     * A source whose one event its owner switched off: absent to every run question, yet it still knows
     * the event's years, the way the calendar answers for a switched-off event.
     */
    private static final OccurrenceSource SWITCHED_OFF = new OccurrenceSource() {
        @Override
        public boolean isEnabled(@Nonnull String eventId) {
            return false;
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
        public Integer firstYear(@Nonnull String eventId) {
            return "spring_fair".equalsIgnoreCase(eventId) ? 2024 : null;
        }

        @Override
        @Nullable
        public Integer currentYear(@Nonnull String eventId, long nowMs) {
            return "spring_fair".equalsIgnoreCase(eventId) ? 2026 : null;
        }
    };

    /** The run {@link #DATED} has still to come. */
    private static final Occurrence NEXT_RUN = new Occurrence("Spring_Fair", 2027, 3_000L, 4_000L);

    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    /** A source that answers the dates: one event, its next run still ahead, its days counted in Tokyo. */
    private static final OccurrenceSource DATED = new OccurrenceSource() {
        @Override
        public boolean isEnabled(@Nonnull String eventId) {
            return "spring_fair".equalsIgnoreCase(eventId);
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
        public Occurrence next(@Nonnull String eventId, long nowMs) {
            return isEnabled(eventId) && nowMs < NEXT_RUN.startMs() ? NEXT_RUN : null;
        }

        @Override
        @Nonnull
        public ZoneId zone(@Nonnull String eventId) {
            return isEnabled(eventId) ? TOKYO : ZoneOffset.UTC;
        }
    };

    @BeforeEach
    @AfterEach
    void emptyTheSlot() {
        Occurrences.resetForTests();
    }

    @Test
    void anUnfilledSlotAnswersThatEveryEventIsAbsent() {
        assertFalse(Occurrences.isFilled());
        OccurrenceSource source = Occurrences.source();
        assertSame(OccurrenceSource.NONE, source);
        assertFalse(source.isEnabled("spring_fair"));
        assertNull(source.live("spring_fair", 1_500L));
        assertTrue(source.history("spring_fair", 1_500L).isEmpty());
        assertNull(source.firstYear("spring_fair"), "no calendar, no years");
        assertNull(source.currentYear("spring_fair", 1_500L));
    }

    @Test
    void theYearsAnswerThroughTheSlotEvenForAnEventThatIsAbsent() {
        Occurrences.fill(SWITCHED_OFF);
        OccurrenceSource source = Occurrences.source();
        assertFalse(source.isEnabled("spring_fair"));
        assertNull(source.live("spring_fair", 1_500L));
        assertEquals(Integer.valueOf(2024), source.firstYear("Spring_Fair"),
                "a switched-off event keeps its years, so what a player earned in a past run keeps them too");
        assertEquals(Integer.valueOf(2026), source.currentYear("spring_fair", 1_500L));
        assertNull(source.firstYear("no_such_event"));
    }

    @Test
    void aSourceThatDoesNotAnswerTheYearsKnowsNone() {
        Occurrences.fill(ONE_RUN);
        assertNull(Occurrences.source().firstYear("spring_fair"), "the default answers nothing");
        assertNull(Occurrences.source().currentYear("spring_fair", 1_500L));
    }

    @Test
    void aSourceThatDoesNotAnswerTheDatesKnowsNoNextRunAndCountsInUtc() {
        OccurrenceSource none = Occurrences.source();
        assertNull(none.next("spring_fair", 500L), "no calendar, no next run");
        assertEquals(ZoneOffset.UTC, none.zone("spring_fair"), "and no clock but UTC");
        Occurrences.fill(ONE_RUN);
        assertNull(Occurrences.source().next("spring_fair", 500L),
                "the default answers no next run, even for an event the source has");
        assertEquals(ZoneOffset.UTC, Occurrences.source().zone("spring_fair"), "and counts its days in UTC");
    }

    @Test
    void theDatesAnswerThroughTheSlot() {
        Occurrences.fill(DATED);
        assertEquals(NEXT_RUN, Occurrences.source().next("Spring_Fair", 1_500L));
        assertEquals(TOKYO, Occurrences.source().zone("Spring_Fair"));
        assertNull(Occurrences.source().next("no_such_event", 1_500L));
        assertEquals(ZoneOffset.UTC, Occurrences.source().zone("no_such_event"));
    }

    @Test
    void aFilledSlotAnswersThroughWhatFilledIt() {
        Occurrences.fill(ONE_RUN);
        assertTrue(Occurrences.isFilled());
        assertEquals(RUN, Occurrences.source().live("Spring_Fair", 1_500L));
        assertNull(Occurrences.source().live("spring_fair", 2_000L), "the end instant is already outside the run");
        assertEquals(List.of(RUN), Occurrences.source().history("spring_fair", 1_500L));
    }

    @Test
    void fillingWithNullEmptiesTheSlotAgain() {
        Occurrences.fill(ONE_RUN);
        Occurrences.fill(null);
        assertFalse(Occurrences.isFilled());
        assertSame(OccurrenceSource.NONE, Occurrences.source());
    }

    @Test
    void anOccurrenceFoldsItsIdAndIsHalfOpen() {
        assertEquals("spring_fair", RUN.eventId());
        assertTrue(RUN.contains(1_000L), "the start instant is in");
        assertFalse(RUN.contains(2_000L), "the end instant is out");
        assertThrows(IllegalArgumentException.class, () -> new Occurrence("spring_fair", 2026, 5L, 5L));
        assertThrows(IllegalArgumentException.class, () -> new Occurrence("  ", 2026, 1L, 2L));
    }
}
