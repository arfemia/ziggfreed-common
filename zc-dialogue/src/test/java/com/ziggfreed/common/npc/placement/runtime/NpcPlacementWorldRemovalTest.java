package com.ziggfreed.common.npc.placement.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a world's removal does to the placement state that has to outlive it (M133).
 *
 * <p>The engine removes every world at a server stop, and a world an admin unloads keeps its folder:
 * either comes back under its name with its NPCs saved in its chunks, and only its ledger rows tell
 * the next sweep which of them were placed. Dropping the rows there made every boot re-adopt the
 * world's NPCs, and a placement without {@code Respawn} whose NPC had been killed came back after a
 * restart. Only a world deleted with its removal (an instance or portal world torn down) takes its
 * rows with it, so it leaves no orphan behind.
 */
class NpcPlacementWorldRemovalTest {

    private static final String KEPT = "default";
    private static final String DELETED = "instance-ruins-1f2e";
    private static final String ANCHOR = "worldspawn:0";
    private static final UUID GUIDE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID WARDEN = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @BeforeEach
    void startClean() {
        NpcPlacementReconciler.clearForTests();
        NpcPlacementPositionCache.invalidateAll();
    }

    /** The singleton must not leak this test's temp file into another test's question. */
    @AfterEach
    void pointBackAtTheDefaultFile() {
        NpcPlacementLedger.getInstance()
                .setFile(Path.of("mods", "ziggfreedcommon", "npc-placement-ledger.json"));
        NpcPlacementPositionCache.invalidateAll();
    }

    // ==================== what a removal keeps ====================

    @Test
    void aKeptWorldsRowsSurviveItsRemovalAndTheRestart(@TempDir Path dir) {
        NpcPlacementLedger ledger = pointAt(dir);
        ledger.record(KEPT, "guide", ANCHOR, GUIDE);

        NpcPlacementReconciler.forgetWorld(KEPT, false);
        ledger.load(); // the next boot reads the file back

        assertEquals(GUIDE, ledger.uuidOf(KEPT, "guide", ANCHOR),
                "the world comes back with its NPCs, and its row is what tells the next sweep the guide"
                        + " was placed: without it a killed guide that does not respawn stands again");
    }

    @Test
    void aKeptWorldKeepsItsCachedPositions(@TempDir Path dir) {
        pointAt(dir).record(KEPT, "guide", ANCHOR, GUIDE);
        NpcPlacementPositionCache.record(KEPT, "guide", ANCHOR, 10, 64, -3);

        NpcPlacementReconciler.forgetWorld(KEPT, false);

        assertNotNull(NpcPlacementPositionCache.get(KEPT, "guide", ANCHOR),
                "a cached position follows its ledger row: the row stayed, so the position is still true");
    }

    // ==================== what a deletion drops ====================

    @Test
    void aDeletedWorldTakesItsRowsAndCachedPositionsWithIt(@TempDir Path dir) {
        NpcPlacementLedger ledger = pointAt(dir);
        ledger.record(DELETED, "warden", ANCHOR, WARDEN);
        ledger.record(KEPT, "guide", ANCHOR, GUIDE);
        NpcPlacementPositionCache.record(DELETED, "warden", ANCHOR, 1, 2, 3);
        NpcPlacementPositionCache.record(KEPT, "guide", ANCHOR, 10, 64, -3);

        NpcPlacementReconciler.forgetWorld(DELETED, true);
        ledger.load();

        assertTrue(ledger.rowsInWorld(DELETED).isEmpty(),
                "a deleted world never comes back under its name, so a row for it would be a permanent orphan");
        assertNull(NpcPlacementPositionCache.get(DELETED, "warden", ANCHOR));
        assertEquals(GUIDE, ledger.uuidOf(KEPT, "guide", ANCHOR), "another world's rows are not touched");
        assertNotNull(NpcPlacementPositionCache.get(KEPT, "guide", ANCHOR));
    }

    // ==================== which removal is a deletion ====================
    //
    // Over the world config's two flags, not a WorldConfig: no test JVM can build one (its class init
    // builds a default gameplay config, which reads the effect asset store).

    @Test
    void aWorldWithNeitherDeleteFlagIsKept() {
        // The default world at a server stop, or a world an admin unloads with /world remove: its
        // folder stays and it loads again under the same name.
        assertFalse(NpcPlacementReconciler.isDeleted(false, false));
    }

    @Test
    void aWorldFlaggedDeleteOnRemoveIsDeleted() {
        // An instance or portal world torn down, a pruned world: the engine deletes its folder right
        // after the removal event, at a server stop as anywhere else.
        assertTrue(NpcPlacementReconciler.isDeleted(true, false));
    }

    @Test
    void aWorldFlaggedDeleteOnUniverseStartIsDeleted() {
        // The next start deletes its folder instead of loading it, so it never comes back either.
        assertTrue(NpcPlacementReconciler.isDeleted(false, true));
    }

    private static NpcPlacementLedger pointAt(Path dir) {
        NpcPlacementLedger ledger = NpcPlacementLedger.getInstance();
        ledger.setFile(dir.resolve("npc-placement-ledger.json"));
        return ledger;
    }
}
