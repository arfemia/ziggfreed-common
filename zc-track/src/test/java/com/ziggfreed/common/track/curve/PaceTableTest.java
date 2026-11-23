package com.ziggfreed.common.track.curve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The Pace lookup and the minutes pricing on it (R609, R618): a level reads the band with the highest start at or
 * below it, a level under every start the first band; minutes pay a sixtieth of the band an hour each; the pricing
 * level is a fixed level when written, else the player's own held at a cap. Mechanics only, never the shipped bands.
 */
class PaceTableTest {

    private static PaceTable bands(Object... levelThenHour) {
        Map<String, Double> written = new LinkedHashMap<>();
        for (int i = 0; i < levelThenHour.length; i += 2) {
            written.put(String.valueOf(levelThenHour[i]), ((Number) levelThenHour[i + 1]).doubleValue());
        }
        return PaceTable.of(written);
    }

    @Test
    void aLevelReadsTheBandItStandsIn() {
        PaceTable pace = bands("10", 200, "1", 100, "30", 400);
        assertEquals(100.0, pace.perHour(1));
        assertEquals(100.0, pace.perHour(9));
        assertEquals(200.0, pace.perHour(10), "a band starts at its own level");
        assertEquals(200.0, pace.perHour(29));
        assertEquals(400.0, pace.perHour(30));
        assertEquals(400.0, pace.perHour(500), "past the last band reads the last");
        assertEquals(100.0, bands("5", 100).perHour(1), "below every start reads the first band");
    }

    @Test
    void aBadKeyOrABandOfNothingIsPassedOver() {
        PaceTable pace = bands("1", 100, "ten", 999, "0", 999, "20", 0, "-5", 999, " 40 ", 300);
        assertEquals(2, pace.bands().size(), "only 1 and 40 read: " + pace);
        assertEquals(100.0, pace.perHour(39), "a band of 0 is taken out, so 20 reads the band from 1");
        assertEquals(300.0, pace.perHour(40), "a key reads with spaces around it");
        assertTrue(PaceTable.of(null).isEmpty());
        assertEquals(0L, PaceTable.NONE.price(30, 50), "a curve with no Pace prices nothing");
    }

    @Test
    void minutesPayTheirShareOfTheBandRoundedWhole() {
        PaceTable pace = bands("1", 16000, "10", 22000);
        assertEquals(2667L, pace.price(10, 1), "10 minutes of 16000 an hour, rounded half up");
        assertEquals(3667L, pace.price(10, 10));
        assertEquals(0L, pace.price(0, 10));
        assertEquals(0L, pace.price(-5, 10));
        assertEquals(0L, pace.price(Double.NaN, 10));
    }

    @Test
    void thePricingLevelIsFixedOrThePlayersOwnUnderItsCap() {
        assertEquals(40, PaceTable.pricingLevel(7, 40, 0), "one-off content prices at its own level");
        assertEquals(40, PaceTable.pricingLevel(90, 40, 30), "a fixed level wins over a cap");
        assertEquals(25, PaceTable.pricingLevel(25, 0, 30), "under the cap, the player's own level");
        assertEquals(30, PaceTable.pricingLevel(60, 0, 30), "past the cap, the cap");
        assertEquals(60, PaceTable.pricingLevel(60, 0, 0), "no cap, the player's own level");
        assertEquals(1, PaceTable.pricingLevel(0, 0, 0), "never below level 1");
    }
}
