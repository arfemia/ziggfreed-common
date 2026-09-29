package com.ziggfreed.common.stats.gearset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The effect seam reports on itself ONCE when consulted unfilled (any of its three members missing),
 * and delegates when filled.
 * Untagged on purpose: the report goes through the guarded logger, and this default test JVM
 * (no log manager, as a consumer's) is where that guard is exercised.
 */
class GearSetEffectsTest {

    @BeforeEach
    @AfterEach
    void startFromNothing() {
        GearSetEffects.resetForTests();
    }

    @Test
    void anUnfilledSeamAnswersFalseAndSaysSoOnce() {
        assertFalse(GearSetEffects.isFilled());
        assertFalse(GearSetEffects.latchForTests().get(), "nothing has been said yet");

        assertFalse(GearSetEffects.warnIfUnfilled(), "the first consult reports");
        assertTrue(GearSetEffects.latchForTests().get(), "and latches");
        assertFalse(GearSetEffects.warnIfUnfilled(), "the second consult stays quiet and still answers false");
        assertFalse(GearSetEffects.warnOnce("said again"), "the once-only latch is shared by the whole seam");
    }

    @Test
    void aFilledSeamDelegatesAllThreeAndNeverReports() {
        List<String> calls = new ArrayList<>();
        GearSetEffects.fill((store, ref, id) -> calls.add("apply " + id),
                (store, ref, id) -> calls.add("remove " + id),
                (store, ref, id) -> calls.add("has " + id));

        assertTrue(GearSetEffects.isFilled());
        assertTrue(GearSetEffects.warnIfUnfilled(), "a filled seam lets the caller proceed");
        assertFalse(GearSetEffects.latchForTests().get(), "and has nothing to report");
        assertTrue(calls.isEmpty(), "asking whether it is filled calls nothing");
    }

    @Test
    void halfAFillIsUnfilled() {
        GearSetEffects.fill((store, ref, id) -> true, null, (store, ref, id) -> true);
        assertFalse(GearSetEffects.isFilled());
        assertFalse(GearSetEffects.warnIfUnfilled());
        assertTrue(GearSetEffects.latchForTests().get());
    }

    @Test
    void aFillWithNoWayToLookIsUnfilledAndAnswersThatTheEntityLacksTheEffect() {
        GearSetEffects.fill((store, ref, id) -> true, (store, ref, id) -> true, null);
        assertFalse(GearSetEffects.isFilled());
        assertFalse(GearSetEffects.has(null, null, "Night_Set_Look"), "unfilled, nothing is found");
        assertTrue(GearSetEffects.latchForTests().get(), "and the gap is reported");
    }

    @Test
    void resetDropsBothTheFillAndTheReport() {
        GearSetEffects.warnIfUnfilled();
        GearSetEffects.fill((store, ref, id) -> true, (store, ref, id) -> true, (store, ref, id) -> true);
        GearSetEffects.resetForTests();

        assertFalse(GearSetEffects.isFilled());
        assertFalse(GearSetEffects.latchForTests().get());
        assertEquals(true, GearSetEffects.warnOnce("after a reset it can be said again"));
    }
}
