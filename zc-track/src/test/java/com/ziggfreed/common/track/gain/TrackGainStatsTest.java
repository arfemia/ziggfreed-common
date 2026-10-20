package com.ziggfreed.common.track.gain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.track.GainCall;
import com.ziggfreed.common.track.TrackDef;
import com.ziggfreed.common.track.TrackKey;
import com.ziggfreed.common.track.TrackRecord;
import com.ziggfreed.common.track.asset.TrackAsset;

/**
 * The Bonus factor's arithmetic (R734): the summed gain stats are held between a gain earning nothing and the ceiling,
 * paid as {@code 1 + percent / 100}, and, run beside a consumer's own boost and the library's boosts in one additive
 * group, a gain still lands at {@code bonus + (boost - 1)} for every boost, the sum the MMO always paid (SK3).
 */
class TrackGainStatsTest {

    private static final GainCall CALL = GainCall.of(TrackKey.player(new UUID(0L, 7L), "Your_Track"),
            new TrackDef(new TrackAsset(), null), TrackRecord.EMPTY, null, 100.0, "test", false);

    @Test
    void theTotalIsHeldBetweenNothingAndTheCeiling() {
        assertEquals(1.0, TrackGainStats.multiplier(0.0, 1000.0), 1e-12, "no stats, no change");
        assertEquals(1.25, TrackGainStats.multiplier(25.0, 1000.0), 1e-12, "25 points pay a quarter more");
        assertEquals(1.5, TrackGainStats.multiplier(80.0, 50.0), 1e-12, "held at the ceiling");
        assertEquals(0.0, TrackGainStats.multiplier(-250.0, 1000.0), 1e-12, "a loss of stats stops a gain at nothing");
        assertEquals(0.0, TrackGainStats.multiplier(30.0, -500.0), 1e-12, "a ceiling below nothing holds at nothing");
        assertEquals(1.0, TrackGainStats.multiplier(Double.NaN, 1000.0), 1e-12, "a total not a number counts none");
        assertEquals(1.0 + TrackGainStats.DEFAULT_CEILING / 100.0,
                TrackGainStats.multiplier(5000.0, Double.NaN), 1e-12, "a ceiling not a number is the default");
    }

    @Test
    void theBonusAddsBesideEveryBoostAsTheMmoAlwaysStackedThem() {
        double bonus = TrackGainStats.multiplier(20.0, 1000.0);
        double mmoBoost = 1.5;
        double boost = 1.25;
        GainStep group = new AdditiveStep(new String[] {"Bonus", "Mmo_Xp_Boost", "Boost"},
                new GainStep[] {GainStep.times(bonus), GainStep.times(mmoBoost), GainStep.times(boost)});
        double legacyBonusStep = bonus + (mmoBoost - 1.0);
        assertEquals(100.0 * (legacyBonusStep + (boost - 1.0)), group.apply(CALL, 100.0), 1e-9,
                "bonus + (boost - 1) over the MMO's boost and the library's alike");
    }
}
