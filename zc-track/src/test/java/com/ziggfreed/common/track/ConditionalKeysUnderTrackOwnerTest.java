package com.ziggfreed.common.track;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.stats.condition.ConditionalStats;
import com.ziggfreed.common.track.asset.TrackAsset;

/**
 * The seam R755's rollback rests on: the conditional stats' {@code Onto} modifiers land on stats 1.7.x knows, so their
 * key sits under the track grants' owner, which the 1.7.x sweep clears, yet no grant key can ever share it (its next
 * segment starts with the run separator no usable track id holds), so the grant rebuild, which leaves exactly that
 * prefix alone, never strips a conditional modifier and never keeps a stale grant.
 */
class ConditionalKeysUnderTrackOwnerTest {

    @Test
    void theConditionalPrefixSitsUnderTheTrackOwner_soTheRollbackSweepClearsIt() {
        assertTrue(ConditionalStats.MODIFIER_PREFIX.startsWith(TrackDerived.OWNER + ":"));
    }

    @Test
    void noGrantKeyCanCarryTheConditionalPrefix() {
        String segment = ConditionalStats.MODIFIER_PREFIX.substring(TrackDerived.OWNER.length() + 1);
        assertEquals(TrackAsset.RUN_SEPARATOR, segment.charAt(0), "the segment opens with the run separator");
        String trackId = segment.substring(0, segment.indexOf(':'));
        assertFalse(TrackAsset.usableId(trackId), "no usable track id is that segment");
        assertFalse(ConditionalStats.writesKey(TrackDerived.grantKey("cond", "pick")),
                "a track named like the segment's word still writes grant keys of its own");
        assertFalse(ConditionalStats.writesKey(TrackDerived.grantKey("Mmo_Swords", TrackDerived.POINTS)));
    }
}
