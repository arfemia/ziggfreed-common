package com.ziggfreed.common.almanac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.factor.FactorProvider;

/** The calendar as the Almanac reads it: its two factors, and silence as absence. */
class FactorAlmanacCalendarTest {

    private static FactorAlmanacCalendar calendar(Map<String, FactorProvider> providers) {
        return new FactorAlmanacCalendar(providers::get);
    }

    @Test
    void withNoCalendarContributedThereIsNoSeason() {
        assertNull(calendar(Map.of()).state("test_season"));
    }

    @Test
    void aLiveSeasonReadsTheYearTheCalendarAnswers() {
        FactorAlmanacCalendar calendar = calendar(Map.of(
                FactorAlmanacCalendar.LIVE_FACTOR, ctx -> "test_season".equals(ctx.param()) ? 1.0 : null,
                FactorAlmanacCalendar.YEAR_FACTOR, ctx -> 2026.0));

        assertEquals(AlmanacCalendar.SeasonState.liveIn(2026), calendar.state("test_season"));
        assertNull(calendar.state("other_season"), "an event the calendar does not answer for is absent");
    }

    @Test
    void zeroReadsBetweenSeasons() {
        FactorAlmanacCalendar calendar = calendar(Map.of(FactorAlmanacCalendar.LIVE_FACTOR, ctx -> 0.0));
        assertEquals(AlmanacCalendar.SeasonState.BETWEEN, calendar.state("test_season"));
    }

    @Test
    void onWithNoYearCountsAsBetweenSoNoSeasonTallyIsWritten() {
        FactorAlmanacCalendar calendar = calendar(Map.of(FactorAlmanacCalendar.LIVE_FACTOR, ctx -> 1.0));
        assertEquals(AlmanacCalendar.SeasonState.BETWEEN, calendar.state("test_season"));
    }

    @Test
    void aThrowingProviderIsNoAnswer() {
        FactorAlmanacCalendar calendar = calendar(Map.of(FactorAlmanacCalendar.LIVE_FACTOR, ctx -> {
            throw new IllegalStateException("boom");
        }));
        assertNull(calendar.state("test_season"));
    }
}
