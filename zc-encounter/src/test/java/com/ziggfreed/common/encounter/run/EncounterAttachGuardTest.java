package com.ziggfreed.common.encounter.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.encounter.run.EncounterAttachGuard.Decision;

/**
 * Which arriving encounter entity carries a run: a script that puts up a boss bar or that a binding
 * names opens one, the engine's bar-less scripts open none, a live run is never touched, and a run the
 * spawner stamped survives a re-add whatever its script says.
 */
class EncounterAttachGuardTest {

    @Test
    void aScriptThatPutsUpABarOpensARun() {
        assertEquals(Decision.OPEN, EncounterAttachGuard.decide(false, false, false, false, true));
    }

    @Test
    void aScriptABindingNamesOpensARunWithNoBar() {
        assertEquals(Decision.OPEN, EncounterAttachGuard.decide(false, false, false, true, false));
    }

    @Test
    void aBarlessUnboundScriptOpensNone() {
        assertEquals(Decision.SKIP, EncounterAttachGuard.decide(false, false, false, false, false),
                "a hunt site, an elite's phase swap or a crystal is not counted as a fight");
    }

    @Test
    void aLiveRunIsKeptWhateverTheScriptSays() {
        assertEquals(Decision.KEEP, EncounterAttachGuard.decide(true, false, true, false, false),
                "the spawner's run on a bar-less script stays");
        assertEquals(Decision.KEEP, EncounterAttachGuard.decide(true, false, false, true, true));
    }

    @Test
    void anEndedRunIsRenewedWhileItsScriptCountsOrTheSpawnerStampedIt() {
        assertEquals(Decision.RENEW, EncounterAttachGuard.decide(true, true, false, false, true));
        assertEquals(Decision.RENEW, EncounterAttachGuard.decide(true, true, false, true, false));
        assertEquals(Decision.RENEW, EncounterAttachGuard.decide(true, true, true, false, false),
                "a spawned run comes back after a builder reload on a bar-less script too");
        assertEquals(Decision.SKIP, EncounterAttachGuard.decide(true, true, false, false, false),
                "an ended run on a script that no longer counts is not renewed");
    }

    @Test
    void onlyTheSpawnersRunIsMarkedSpawnedAndItsCopyKeepsTheMark() {
        ZigEncounterRun spawned = ZigEncounterRun.forSpawn(SpawnOptions.defaults());
        assertTrue(spawned.spawned());
        assertTrue(((ZigEncounterRun) spawned.clone()).spawned(), "a re-add's copy keeps the mark");
        assertFalse(new ZigEncounterRun().spawned(), "a run the attach opened is not the spawner's");
    }
}
