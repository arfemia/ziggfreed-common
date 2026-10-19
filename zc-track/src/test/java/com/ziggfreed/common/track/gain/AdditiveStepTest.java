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
 * The factors a track adds together (Bonus and Boost unless it says otherwise) pay the MMO's
 * {@code amount * (bonus + (boost - 1))}, never {@code amount * bonus * boost}: each member's extra adds onto the
 * same amount, a member that stops the gain takes only its own share away, and the whole never goes below nothing.
 */
class AdditiveStepTest {

    private static final GainCall CALL = GainCall.of(TrackKey.player(new UUID(0L, 42L), "Your_Track"),
            new TrackDef(new TrackAsset(), null), TrackRecord.EMPTY, null, 100.0, "test", false);

    private static double stacked(double amount, double... multipliers) {
        GainStep[] members = new GainStep[multipliers.length];
        String[] ids = new String[multipliers.length];
        for (int i = 0; i < multipliers.length; i++) {
            members[i] = GainStep.times(multipliers[i]);
            ids[i] = "Factor_" + i;
        }
        return new AdditiveStep(ids, members).apply(CALL, amount);
    }

    @Test
    void theMembersExtrasAddOntoTheSameAmount() {
        assertEquals(170.0, stacked(100.0, 1.2, 1.5), 1e-9, "1 + 0.2 + 0.5, not 1.2 * 1.5");
        assertEquals(100.0 * (1.25 + (3.0 - 1.0)), stacked(100.0, 1.25, 3.0), 1e-9,
                "bonus + (boost - 1), to the MMO's own sum");
        assertEquals(150.0, stacked(100.0, 1.0, 1.5), 1e-9, "a neutral bonus leaves the boost as it is");
        assertEquals(180.0, stacked(100.0, 1.2, 1.5, 1.1), 1e-9, "any number of members");
    }

    @Test
    void aMemberThatStopsTheGainTakesOnlyItsOwnShare() {
        assertEquals(50.0, stacked(100.0, 0.0, 1.5), 1e-9, "a bonus of 0 beside a boost of 1.5 pays half");
        assertEquals(0.0, stacked(100.0, 0.0, 0.0, 1.5), 1e-9, "and the whole never goes below nothing");
    }
}
