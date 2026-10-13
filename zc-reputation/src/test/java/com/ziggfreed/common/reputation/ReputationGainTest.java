package com.ziggfreed.common.reputation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The worn reputation gain: the global stat and the reputation's own add up, a gain is rounded half up and never
 * falls under 1, a loss is never scaled, the total is held to its rails, and the Cap still cuts the raised gain.
 */
class ReputationGainTest {

    private FakeReputationNative engine;
    private ReputationService service;

    @BeforeEach
    void seed() {
        ReputationFixtures.reset();
        engine = ReputationFixtures.engine();
        service = new ReputationService(engine, ReputationFanOut.NONE);
    }

    @AfterEach
    void clear() {
        ReputationFixtures.reset();
    }

    @Test
    void aGainIsRaisedByThePercentRoundedHalfUpAndNeverUnderOne() {
        assertEquals(110, ReputationGain.scale(100, 10));
        assertEquals(250, ReputationGain.scale(250, 0), "no bonus, the amount written");
        assertEquals(17, ReputationGain.scale(15, 10), "16.5 rounds up");
        assertEquals(11, ReputationGain.scale(15, -30), "10.5 rounds up too, with no floating-point slip");
        assertEquals(1, ReputationGain.scale(1, -50), "half of one still pays one");
        assertEquals(1, ReputationGain.scale(7, -100), "the lowest total shrinks a gain to its floor of one");
    }

    @Test
    void aLossOrAZeroIsNeverScaled() {
        assertEquals(-200, ReputationGain.scale(-200, 40));
        assertEquals(-200, ReputationGain.scale(-200, -100));
        assertEquals(0, ReputationGain.scale(0, 40));
    }

    @Test
    void theTotalIsHeldToItsRails() {
        assertEquals(1, ReputationGain.scale(50, -500), "below -100 counts as -100");
        assertEquals(1_100, ReputationGain.scale(100, 50_000), "past the ceiling counts as the ceiling");
        assertEquals(Integer.MAX_VALUE, ReputationGain.scale(Integer.MAX_VALUE, ReputationGain.MAX_PERCENT),
                "a huge gain never overflows");
    }

    @Test
    void theWriterAddsTheGlobalAndTheReputationsOwnAndTheCapStillWins() {
        engine.stats.put(ReputationGain.GLOBAL_STAT, 10L);
        engine.stats.put(ReputationGain.statFor(ReputationFixtures.OLD_JACK), 20L);
        assertEquals(130, service.change(null, null, "test_old_jack", 100, "t").change().delta(),
                "+10% and +20% add up to +30%, and the change carries what moved");
        assertEquals(-100, service.change(null, null, "Test_Old_Jack", -100, "t").change().delta(),
                "a loss is never raised");
        ReputationFixtures.loadCompanions(Map.of("test_old_jack",
                ReputationFixtures.companion(ReputationFixtures.OLD_JACK, "{ \"Cap\": 1000 }")));
        engine.stored.put("Test_Old_Jack", 900);
        assertEquals(1_000, service.change(null, null, "Test_Old_Jack", 100, "t").change().earnedAfter(),
                "the raised gain of 130 is cut at the Cap");
        assertEquals(List.of("Test_Old_Jack 130", "Test_Old_Jack -100", "Test_Old_Jack 100"), engine.writes,
                "the engine is handed the raised gain, cut to the Cap before the write");
    }
}
