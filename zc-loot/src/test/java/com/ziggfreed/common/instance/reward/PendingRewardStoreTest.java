package com.ziggfreed.common.instance.reward;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The queue's durable file speaks a versioned shape, and the version rules are the ones every
 * durable store here shares: a file written before the marker existed reads as version 1 (the
 * MMO's first-boot migration drains 1.5.x-era files through exactly that door), every write
 * carries the version, and a future-shaped or unreadable file is left unread, and unwritten, rather
 * than misread or overwritten.
 *
 * <p>Two tests need the engine's own atomic writer to run (one reads back a file the store wrote, one
 * proves a refused file outlives a write), and on Update 7 it loads only under the engine's log
 * manager, so those two alone are tagged {@code engine-items}. The read tests, and the guard that
 * keeps a write the engine cannot make from reaching a caller, run in the plain {@code test} JVM,
 * where a consumer mod's own tests run.
 */
class PendingRewardStoreTest {

    private static final UUID OWED = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @Test
    void aPreVersionFileStillLoadsAsVersionOne(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pending.json"), """
                { "players": { "%s": [ { "kind": "CURRENCY", "id": "bounty_token",
                                         "quantity": 25 } ] } }
                """.formatted(OWED));

        PendingRewardStore store = new PendingRewardStore("pending");
        store.init(dir);

        assertTrue(store.has(OWED), "a file with no version marker reads exactly as it always did");
        List<InstanceReward> drained = store.drain(OWED);
        assertEquals(1, drained.size());
        assertEquals("bounty_token", drained.get(0).id());
        assertEquals(25, drained.get(0).quantity());
    }

    /**
     * Tagged {@code engine-items}: the write goes through the engine's own atomic writer
     * ({@code FileUtil.writeStringAtomic}), and on Update 7 that class logs at class init, which only
     * the engine's log manager allows ({@code engineItemTest}). In the plain {@code test} JVM the write
     * cannot happen at all, so this test needs the engine to see the file it proves; the guard is
     * pinned untagged below.
     */
    @Tag("engine-items")
    @Test
    void everyWriteCarriesTheVersion(@TempDir Path dir) throws IOException {
        PendingRewardStore store = new PendingRewardStore("pending");
        store.init(dir);

        store.queue(OWED, List.of(InstanceReward.currency("bounty_token", 25, null)));

        JsonObject root = JsonParser
                .parseString(Files.readString(dir.resolve("pending.json"))).getAsJsonObject();
        assertEquals(1, root.get("version").getAsInt());
        assertTrue(root.getAsJsonObject("players").has(OWED.toString()));

        PendingRewardStore reread = new PendingRewardStore("pending");
        reread.init(dir);
        assertTrue(reread.has(OWED), "and the versioned file round-trips");
    }

    @Test
    void aFileDeclaringANewerVersionIsLeftUnread(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pending.json"), """
                { "version": 2,
                  "players": { "%s": [ { "kind": "CURRENCY", "id": "bounty_token",
                                         "quantity": 25 } ] } }
                """.formatted(OWED));

        PendingRewardStore store = new PendingRewardStore("pending");
        store.init(dir);

        assertFalse(store.has(OWED),
                "a future-shaped file is refused whole rather than misread as today's shape");
    }

    /**
     * A file the store refused at load (a newer version, or one it cannot read) is still on disk, byte
     * for byte, after a queue and a drain have both written, so a downgrade or a damaged file never
     * costs a player what they were owed. Tagged {@code engine-items}: in the plain {@code test} JVM no
     * write can happen at all, so the file would survive without the latch; here the engine's writer
     * runs (the control store proves it), so only the latch keeps the file whole.
     */
    @Tag("engine-items")
    @Test
    void aRefusedFileSurvivesTheNextWrite(@TempDir Path dir) throws IOException {
        Path control = Files.createDirectories(dir.resolve("control"));
        PendingRewardStore fresh = new PendingRewardStore("pending");
        fresh.init(control);
        fresh.queue(OWED, List.of(InstanceReward.currency("bounty_token", 25, null)));
        assertTrue(Files.exists(control.resolve("pending.json")), "the engine's writer runs in this JVM");

        assertSurvivesAWrite(Files.createDirectories(dir.resolve("newer")), """
                { "version": 2,
                  "players": { "%s": [ { "kind": "CURRENCY", "id": "bounty_token",
                                         "quantity": 25 } ] } }
                """.formatted(OWED), "a newer file waits for the build that reads it");
        assertSurvivesAWrite(Files.createDirectories(dir.resolve("unreadable")),
                "{ \"version\": 1, \"players\": { \"" + OWED + "\": [ { \"kind\": \"CURR",
                "a file cut short mid-write is never replaced");
    }

    private static void assertSurvivesAWrite(Path dir, String original, String why) throws IOException {
        Path file = dir.resolve("pending.json");
        Files.writeString(file, original);

        PendingRewardStore store = new PendingRewardStore("pending");
        store.init(dir);
        store.queue(OWED, List.of(InstanceReward.currency("bounty_token", 5, null)));
        assertEquals(1, store.drain(OWED).size(), "the queue still answers from memory");

        assertEquals(original, Files.readString(file), why);
    }

    /**
     * The store in a JVM whose engine writer cannot run (a consumer mod's unit tests, or this module's
     * plain {@code test} task on Update 7): queueing and draining never throw, and the queue answers
     * from memory, whatever the write does. Untagged on purpose: it is the guard, and only the plain
     * JVM proves it.
     */
    @Test
    void aWriteThatCannotHappenNeverReachesTheCaller(@TempDir Path dir) {
        PendingRewardStore store = new PendingRewardStore("pending");
        store.init(dir);

        assertDoesNotThrow(() -> store.queue(OWED, List.of(InstanceReward.currency("bounty_token", 25, null))));
        assertTrue(store.has(OWED), "the queue answers from memory");
        List<InstanceReward> drained = assertDoesNotThrow(() -> store.drain(OWED));
        assertEquals(1, drained.size());
        assertFalse(store.has(OWED));
    }
}
