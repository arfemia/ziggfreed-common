package com.ziggfreed.common.encounter.seam;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Each root-filled seam REPORTS ON ITSELF, once, the first time it has to fall back, and never when
 * it is filled: the shape the library's seam rule asks of a seam a unit test cannot see filled.
 */
class EncounterSeamsTest {

    @BeforeEach
    void reset() {
        EncounterSeams.resetForTests();
    }

    @AfterEach
    void tearDown() {
        EncounterSeams.resetForTests();
    }

    @Test
    void theAttributionSeamSaysOnceThatNobodyFilledIt() {
        assertNull(EncounterSeams.attributionOrWarn(), "nothing is filled, so the answer is nobody");
        assertFalse(EncounterSeams.warnOnce(latchOf("attribution"), "again"),
                "the report is spent on the first fall-back and never repeated");
        EncounterSeams.fillAttribution((store, ref) -> null);
        EncounterSeams.resetForTests();
        EncounterSeams.fillAttribution((store, ref) -> null);
        assertNotNull(EncounterSeams.attributionOrWarn(), "a filled seam answers");
        assertTrue(EncounterSeams.warnOnce(latchOf("attribution"), "unspent"),
                "and the report is still unspent, which pins that nothing was said about a filled seam");
    }

    @Test
    void thePowerSeamSaysOnceThatNobodyFilledIt() {
        assertNull(EncounterSeams.powerSourceOrWarn());
        assertFalse(EncounterSeams.warnOnce(latchOf("power"), "again"));
        EncounterSeams.resetForTests();
        EncounterSeams.fillPowerSource((store, subject, members) -> 12.0);
        assertNotNull(EncounterSeams.powerSourceOrWarn());
        assertTrue(EncounterSeams.warnOnce(latchOf("power"), "unspent"));
    }

    @Test
    void theSubjectSeamSaysOnceThatNobodyFilledIt() {
        assertNull(EncounterSeams.subjectSourceOrWarn());
        assertFalse(EncounterSeams.warnOnce(latchOf("subjects"), "again"));
        EncounterSeams.resetForTests();
        EncounterSeams.fillSubjectSource((store, ref) -> null);
        assertNotNull(EncounterSeams.subjectSourceOrWarn());
        assertTrue(EncounterSeams.warnOnce(latchOf("subjects"), "unspent"));
    }

    @Test
    void theRewardQueueSeamSaysOnceThatNobodyFilledIt() {
        assertNull(EncounterSeams.rewardQueueOrWarn());
        assertFalse(EncounterSeams.warnOnce(latchOf("rewardQueue"), "again"));
        EncounterSeams.resetForTests();
        EncounterSeams.fillRewardQueue(() -> null);
        assertNotNull(EncounterSeams.rewardQueueOrWarn());
        assertTrue(EncounterSeams.warnOnce(latchOf("rewardQueue"), "unspent"));
    }

    @Test
    void anUnfilledPowerSeamReadsZeroAndAnUnfilledQueueReadsNull() {
        assertNull(EncounterSeams.rewardQueue());
        assertFalse(EncounterSeams.isPowerSourceFilled());
        assertFalse(EncounterSeams.isAttributionFilled());
        assertFalse(EncounterSeams.isSubjectSourceFilled());
        assertFalse(EncounterSeams.isRewardQueueFilled());
    }

    private static AtomicBoolean latchOf(String seam) {
        return EncounterSeams.latchForTests(seam);
    }
}
