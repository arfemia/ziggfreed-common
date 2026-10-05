package com.ziggfreed.common.encounter.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * How an add finds its run and waits for its tick: the encounter's lineage names the run, an add is
 * held once until the tick takes it, a requeued add keeps counting its wait, a run's end forgets
 * both, and one run never holds more than its cap. References are bare test refs; no store needed.
 */
class EncounterRunsAddsTest {

    private static final UUID RUN = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @AfterEach
    void tearDown() {
        EncounterRuns.resetForTests();
    }

    private static Ref<EntityStore> add(int index) {
        return new Ref<>((Store<EntityStore>) null, index);
    }

    @Test
    void aLineageNamesTheRunItsEncounterCarries() {
        EncounterRuns.indexLineage(RUN, "lineage-a");
        assertEquals(RUN, EncounterRuns.runOfLineage("lineage-a"));
        assertNull(EncounterRuns.runOfLineage("lineage-b"), "another encounter's spawn is not this run's add");
        assertNull(EncounterRuns.runOfLineage(null));
    }

    @Test
    void reIndexingARunDropsWhatItPointedAtBefore() {
        EncounterRuns.indexLineage(RUN, "lineage-a");
        EncounterRuns.indexLineage(RUN, "lineage-b");
        assertNull(EncounterRuns.runOfLineage("lineage-a"));
        assertEquals(RUN, EncounterRuns.runOfLineage("lineage-b"));
    }

    @Test
    void anAddWaitsOnItsRunUntilTakenAndIsHeldOnce() {
        Ref<EntityStore> blight = add(7);
        assertTrue(EncounterRuns.notePendingAdd(RUN, blight));
        assertTrue(EncounterRuns.notePendingAdd(RUN, blight), "noting it again is harmless");
        assertEquals(Map.of(blight, 0), EncounterRuns.takePendingAdds(RUN));
        assertTrue(EncounterRuns.takePendingAdds(RUN).isEmpty(), "taken once, forgotten");
    }

    @Test
    void aRequeuedAddKeepsCountingItsWait() {
        Ref<EntityStore> blight = add(7);
        EncounterRuns.requeuePendingAdd(RUN, blight, 3);
        assertEquals(Map.of(blight, 3), EncounterRuns.takePendingAdds(RUN));
    }

    @Test
    void endingARunForgetsItsLineageAndItsAdds() {
        EncounterRuns.indexLineage(RUN, "lineage-a");
        EncounterRuns.notePendingAdd(RUN, add(1));
        EncounterRuns.untrack(RUN);
        assertNull(EncounterRuns.runOfLineage("lineage-a"));
        assertTrue(EncounterRuns.takePendingAdds(RUN).isEmpty());
    }

    @Test
    void aRunHoldsAtMostItsCapOfWaitingAdds() {
        for (int i = 0; i < EncounterRuns.MAX_PENDING_ADDS; i++) {
            assertTrue(EncounterRuns.notePendingAdd(RUN, add(i)));
        }
        assertFalse(EncounterRuns.notePendingAdd(RUN, add(EncounterRuns.MAX_PENDING_ADDS)),
                "past the cap a new add is let go");
        assertEquals(EncounterRuns.MAX_PENDING_ADDS, EncounterRuns.takePendingAdds(RUN).size());
    }
}
