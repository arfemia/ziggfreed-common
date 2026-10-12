package com.ziggfreed.common.encounter.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The member index across a reset: a run that carries on keeps its members indexed on the next run until
 * that run's first tick re-indexes its own roster, so a reward granted in between finds a run that has
 * ended and refuses, where a dropped index read as a player in no run and paid a full share. A run that
 * ends with its entity drops them. References are bare test refs; no store needed.
 */
class EncounterRunsMembersTest {

    private static final UUID RUN = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID NEXT = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @AfterEach
    void tearDown() {
        EncounterRuns.resetForTests();
    }

    private static Ref<EntityStore> member(int index) {
        return new Ref<>((Store<EntityStore>) null, index);
    }

    @Test
    void aContinuingResetKeepsItsMembersOnTheNextRun() {
        Ref<EntityStore> a = member(1);
        Ref<EntityStore> b = member(2);
        Ref<EntityStore> c = member(3);
        EncounterRuns.indexMembers(RUN, List.of(a, b));
        EncounterRuns.indexMembers(OTHER, List.of(c));

        EncounterRuns.untrack(RUN, NEXT, null);

        assertEquals(NEXT, EncounterRuns.runOfMember(a));
        assertEquals(NEXT, EncounterRuns.runOfMember(b));
        assertNull(EncounterRuns.live(NEXT), "the next run is not live before its first tick");
        assertEquals(OTHER, EncounterRuns.runOfMember(c), "another run's members are untouched");
    }

    @Test
    void theNextRunsFirstTickReplacesTheMembersItWasHanded() {
        Ref<EntityStore> stayed = member(1);
        Ref<EntityStore> left = member(2);
        EncounterRuns.indexMembers(RUN, List.of(stayed, left));
        EncounterRuns.untrack(RUN, NEXT, null);

        EncounterRuns.indexMembers(NEXT, List.of(stayed));

        assertEquals(NEXT, EncounterRuns.runOfMember(stayed));
        assertNull(EncounterRuns.runOfMember(left), "a player off the roster is no longer held");
    }

    @Test
    void aRunEndingWithItsEntityDropsItsMembers() {
        Ref<EntityStore> a = member(1);
        EncounterRuns.indexMembers(RUN, List.of(a));

        EncounterRuns.untrack(RUN);

        assertNull(EncounterRuns.runOfMember(a));
    }
}
