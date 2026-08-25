package com.ziggfreed.common.world.placed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The ledger's whole contract: what it refuses, who it refuses it to, how long it remembers, and
 * the several-readers window that lets two systems reading one native event agree.
 */
class PlacedBlockLedgerTest {

    /** Who the test installs its policy as; the ledger holds one slot and names its owner. */
    private static final String OWNER = "test";

    /** Every call in a test is its own moment unless it is deliberately pretending otherwise. */
    private static final long MOMENT = 4001L;

    private static final UUID PLACER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID NEIGHBOUR = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
    private static final UUID OTHER_WORLD = UUID.fromString("00000000-0000-0000-0000-0000000000d4");

    private final PlacedBlockLedger ledger = PlacedBlockLedger.getInstance();

    /** Where the shipped ledger writes, captured so a test never leaves it pointing nowhere. */
    private final Path shippedFile = ledger.file();

    @BeforeEach
    void reset() {
        ledger.clear();
        ledger.setPolicy(OWNER, null);
        ledger.setFile(shippedFile);
    }

    @AfterEach
    void tearDown() {
        // Join any background write first, so a writer cannot race the TempDir cleanup.
        ledger.awaitBackgroundWrite();
        ledger.clear();
        ledger.setPolicy(OWNER, null);
        ledger.setFile(shippedFile);
    }

    /** The knobs a test wants to vary, each independently. */
    private record TestPolicy(boolean enabled, boolean strict, int blockExpireMinutes,
            int itemExpireMinutes) implements PlacedBlockLedger.Policy {
    }

    @Test
    void aBlockNobodyPlacedIsNotPlaced() {
        assertFalse(ledger.consumePlacement(PLACER, WORLD, 1, 2, 3));
    }

    @Test
    void aBlockThePlacerBreaksThemselvesIsRefused() {
        ledger.trackPlacement(PLACER, WORLD, 1, 2, 3);

        assertTrue(ledger.isPlaced(PLACER, WORLD, 1, 2, 3));
        assertTrue(ledger.consumePlacement(PLACER, WORLD, 1, 2, 3));
    }

    @Test
    void positionsAreScopedByWorld() {
        ledger.trackPlacement(PLACER, WORLD, 1, 2, 3);

        assertFalse(ledger.consumePlacement(PLACER, OTHER_WORLD, 1, 2, 3),
                "the same coordinates in another world are another block");
    }

    /**
     * The NON-strict reading, which a policy has to ask for: a neighbour mining what you built is
     * doing ordinary work, so only the placer is refused their own placement. The library's own
     * default is the other way round (see the test below it).
     */
    @Test
    void withoutStrictOnlyThePlacerIsRefusedTheirOwnPlacement() {
        ledger.setPolicy(OWNER, new TestPolicy(true, false, 0, 5));
        ledger.trackPlacement(PLACER, WORLD, 1, 2, 3);

        assertFalse(ledger.consumePlacement(NEIGHBOUR, WORLD, 1, 2, 3));
        assertTrue(ledger.consumePlacement(PLACER, WORLD, 1, 2, 3));
    }

    @Test
    void strictRefusesEverybodyAnyPlacement() {
        ledger.setPolicy(OWNER, new TestPolicy(true, true, 0, 5));
        ledger.trackPlacement(PLACER, WORLD, 1, 2, 3);

        assertTrue(ledger.consumePlacement(NEIGHBOUR, WORLD, 1, 2, 3),
                "strict is the answer for a server where players would hand each other ore");
    }

    @Test
    void disabledRemembersNothingAndRefusesNobody() {
        ledger.setPolicy(OWNER, new TestPolicy(false, true, 0, 5));
        ledger.trackPlacement(PLACER, WORLD, 1, 2, 3);
        ledger.trackPlacedItem(PLACER, "Sapling");

        assertFalse(ledger.consumePlacement(PLACER, WORLD, 1, 2, 3));
        assertFalse(ledger.consumePlacedItem(PLACER, "Sapling", MOMENT));
        assertEquals(0, ledger.trackedBlockCount());
    }

    /**
     * The reason a consumed row is not simply dropped: one break is read by the library's producer
     * AND by every consumer's own event system, in an order nobody specifies.
     */
    @Test
    void everyReaderOfOneBreakGetsTheSameAnswer() {
        ledger.trackPlacement(PLACER, WORLD, 4, 5, 6);

        assertTrue(ledger.consumePlacement(PLACER, WORLD, 4, 5, 6), "the first reader");
        assertTrue(ledger.consumePlacement(PLACER, WORLD, 4, 5, 6), "the second reader, same moment");
        assertTrue(ledger.consumePlacement(PLACER, WORLD, 4, 5, 6), "and a third");
    }

    /**
     * Re-filling a spot you already filled leaves ONE row, not two. A consumed row lingers for the
     * grace window rather than vanishing, so the fresh placement has to take the position off the
     * old row's hands: two rows for one position would over-report the ledger, write both to the
     * file, and let a restart hand the position back the OLDER timestamp - which on a server with an
     * expiry window opens the guard earlier than the owner asked for.
     */
    @Test
    void rePlacingAPositionYouAlreadyUsedLeavesOneRow() {
        ledger.trackPlacement(PLACER, WORLD, 1, 2, 3);
        assertTrue(ledger.consumePlacement(PLACER, WORLD, 1, 2, 3));

        ledger.trackPlacement(PLACER, WORLD, 1, 2, 3);

        assertEquals(1, ledger.trackedBlockCount(), "one position, one row");
        assertTrue(ledger.consumePlacement(PLACER, WORLD, 1, 2, 3),
                "and the fresh placement is still refused its placer");
    }

    /**
     * Aging out, driven from a hand-written file because that is the only way a test can hold a
     * placement that happened an hour ago. Zero minutes is "remember it for good", which is the
     * library's own default and the reason a wall built yesterday still pays nobody.
     *
     * <p>Every ask applies the LIVE window, and housekeeping is what actually drops the row: the
     * load itself must not, because a consumer installs its policy after the library has booted and
     * an expiry applied at load would be measured against the library's number rather than the
     * owner's.
     */
    @Test
    void aPlacementAgesOutOnlyWhenTheExpiryWindowIsSet(@TempDir Path dir) throws Exception {
        long anHourAgo = System.currentTimeMillis() - 60 * 60_000L;
        Path file = dir.resolve("placed-blocks.json");
        Files.writeString(file, "{\"" + PLACER + "\":[{\"worldUuid\":\"" + WORLD
                + "\",\"x\":7,\"y\":8,\"z\":9,\"placedTime\":" + anHourAgo + "}]}");
        ledger.setFile(file);

        ledger.setPolicy(OWNER, new TestPolicy(true, true, 0, 5));
        ledger.load();
        assertTrue(ledger.isPlaced(PLACER, WORLD, 7, 8, 9),
                "no expiry window means the ledger never forgets");

        ledger.setPolicy(OWNER, new TestPolicy(true, true, 1, 5));
        ledger.load();
        assertFalse(ledger.isPlaced(PLACER, WORLD, 7, 8, 9),
                "an hour-old placement is past a one-minute window, whenever it was read back");
        assertFalse(ledger.consumePlacement(PLACER, WORLD, 7, 8, 9));
        ledger.cleanupExpired();
        assertEquals(0, ledger.trackedBlockCount(), "and housekeeping reclaims it");
    }

    /**
     * A policy installed by somebody else does not silently share the slot with the first, and the
     * later one is what the ledger answers with.
     */
    @Test
    void theLastPolicyInstalledIsTheOneInForce() {
        ledger.setPolicy(OWNER, new TestPolicy(true, false, 0, 5));
        ledger.setPolicy("another-mod", new TestPolicy(true, true, 0, 5));
        ledger.trackPlacement(PLACER, WORLD, 1, 2, 3);

        assertTrue(ledger.consumePlacement(NEIGHBOUR, WORLD, 1, 2, 3),
                "the second policy is strict, and it is the one in force");
    }

    @Test
    void aPlacedItemIsRefusedOncePerCopyPlaced() {
        ledger.setPolicy(OWNER, new TestPolicy(true, true, 0, 5));
        ledger.trackPlacedItem(PLACER, "Sapling");
        ledger.trackPlacedItem(PLACER, "Sapling");

        assertTrue(ledger.consumePlacedItem(PLACER, "Sapling", 1L), "the first copy back");
        assertEquals(1, ledger.trackedItemCount());
        assertTrue(ledger.consumePlacedItem(PLACER, "Sapling", 2L), "the second copy back");
        assertFalse(ledger.consumePlacedItem(NEIGHBOUR, "Sapling", 3L),
                "an item carries no memory of who dropped it, so only the picker's own count answers");
    }

    /**
     * The failure this half exists to make impossible: TWO copies placed, TWO systems reading each
     * pickup. Without a moment key the second reader would spend the second copy, and the player's
     * next pickup - the one that should still be refused - would be credited instead.
     */
    @Test
    void twoReadersOfOnePickupSpendOneCopyBetweenThem() {
        ledger.setPolicy(OWNER, new TestPolicy(true, true, 0, 5));
        ledger.trackPlacedItem(PLACER, "Sapling");
        ledger.trackPlacedItem(PLACER, "Sapling");

        assertTrue(ledger.consumePlacedItem(PLACER, "Sapling", 11L), "the library's producer");
        assertTrue(ledger.consumePlacedItem(PLACER, "Sapling", 11L), "and a consumer's XP path");
        assertEquals(1, ledger.trackedItemCount(),
                "one pickup, one copy, however many systems read the event");

        assertTrue(ledger.consumePlacedItem(PLACER, "Sapling", 12L), "the second pickup is its own");
        assertTrue(ledger.consumePlacedItem(PLACER, "Sapling", 12L), "read twice as well");
        assertEquals(0, ledger.trackedItemCount());

        assertFalse(ledger.consumePlacedItem(PLACER, "Sapling", 13L),
                "and a third pickup is a genuine find, because only two were ever put down");
    }

    @Test
    void anItemNobodyPlacedIsNotRefused() {
        ledger.trackPlacedItem(PLACER, "Sapling");

        assertFalse(ledger.consumePlacedItem(PLACER, "Iron_Ore", MOMENT));
    }

    @Test
    void placementsSurviveARestartAndPlacedItemsDoNot(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("placed-blocks.json");
        ledger.setFile(file);
        ledger.trackPlacement(PLACER, WORLD, 10, 11, 12);
        ledger.trackPlacedItem(PLACER, "Sapling");
        ledger.save();
        assertTrue(Files.exists(file));

        ledger.clear();
        ledger.load();

        assertTrue(ledger.consumePlacement(PLACER, WORLD, 10, 11, 12),
                "a placement outlives the restart");
        assertFalse(ledger.consumePlacedItem(PLACER, "Sapling", MOMENT),
                "a placed item does not, because it would have expired before the server came back");
    }

    @Test
    void anEmptyLedgerLeavesNoFileBehind(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("placed-blocks.json");
        ledger.setFile(file);
        ledger.trackPlacement(PLACER, WORLD, 1, 1, 1);
        ledger.save();
        assertTrue(Files.exists(file));

        ledger.clearPlayer(PLACER);
        ledger.save();

        assertFalse(Files.exists(file));
        assertEquals(0, ledger.trackedPlayerCount());
    }

    // ==================== the debounced off-thread flush ====================

    @Test
    void mutatorsMarkTheLedgerDirtyButAConsumedAtBumpAloneDoesNot(@TempDir Path dir) throws Exception {
        ledger.setFile(dir.resolve("placed-blocks.json"));
        ledger.save(); // pins the beat clock so no internal beat fires mid-test
        assertFalse(ledger.isDirty());

        ledger.trackPlacement(PLACER, WORLD, 1, 2, 3);
        assertTrue(ledger.isDirty(), "a placement is file-visible state");
        ledger.save();
        assertFalse(ledger.isDirty(), "a save leaves the ledger clean");

        assertTrue(ledger.consumePlacement(PLACER, WORLD, 1, 2, 3));
        assertFalse(ledger.isDirty(),
                "a first consumption only bumps the in-memory consumedAt, which the file never carries");

        ledger.trackPlacement(PLACER, WORLD, 4, 5, 6);
        ledger.save();
        ledger.clearPlayer(PLACER);
        assertTrue(ledger.isDirty(), "clearing a player drops file-visible rows");
    }

    @Test
    void theTrafficBeatFlushesOffThreadOnlyAfterTheIntervalCap(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("placed-blocks.json");
        ledger.setFile(file);
        ledger.save(); // pins the beat clock so only this test's own beats fire
        long base = System.currentTimeMillis() + PlacedBlockLedger.FLUSH_INTERVAL_MS + 1_000L;

        ledger.trackPlacement(PLACER, WORLD, 1, 2, 3);
        ledger.maybeFlush(base);
        ledger.awaitBackgroundWrite();
        assertTrue(Files.exists(file), "an elapsed interval flushes the dirty ledger");

        Files.delete(file);
        ledger.trackPlacement(PLACER, WORLD, 4, 5, 6);
        ledger.maybeFlush(base + PlacedBlockLedger.FLUSH_INTERVAL_MS - 1);
        ledger.awaitBackgroundWrite();
        assertFalse(Files.exists(file), "inside the cap the beat must not write");

        ledger.maybeFlush(base + PlacedBlockLedger.FLUSH_INTERVAL_MS);
        ledger.awaitBackgroundWrite();
        assertTrue(Files.exists(file), "once the cap elapses the beat flushes again");
    }

    @Test
    void saveWritesUnconditionallyEvenWhenClean(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("placed-blocks.json");
        ledger.setFile(file);
        ledger.trackPlacement(PLACER, WORLD, 1, 2, 3);
        ledger.save();
        assertTrue(Files.exists(file));
        assertFalse(ledger.isDirty());

        Files.delete(file);
        ledger.save();
        assertTrue(Files.exists(file), "the shutdown save writes even when nothing is dirty");
    }

    @Test
    void aShutdownSaveIsNeverRolledBackByAnInFlightBackgroundFlush(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("placed-blocks.json");
        ledger.setFile(file);
        ledger.save(); // pins the beat clock
        long base = System.currentTimeMillis() + PlacedBlockLedger.FLUSH_INTERVAL_MS + 1_000L;

        ledger.trackPlacement(PLACER, WORLD, 1, 2, 3);
        ledger.maybeFlush(base); // hands the one-row snapshot to the background writer
        ledger.trackPlacement(PLACER, WORLD, 4, 5, 6);
        ledger.save(); // awaits the writer, then writes the newer two-row state, stamped newest

        ledger.awaitBackgroundWrite();
        ledger.clear();
        ledger.load();
        assertTrue(ledger.isPlaced(PLACER, WORLD, 1, 2, 3));
        assertTrue(ledger.isPlaced(PLACER, WORLD, 4, 5, 6),
                "the save's newer snapshot must be the one on disk; a background straggler may not roll it back");
    }
}
