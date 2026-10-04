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
 * carries the version, and a future-shaped file is left unread rather than misread.
 *
 * <p>One test reads back a file the store wrote through the engine's own atomic writer, which on
 * Update 7 loads only under the engine's log manager, so it alone is tagged {@code engine-items}. The
 * read tests, and the guard that keeps a write the engine cannot make from reaching a caller, run in
 * the plain {@code test} JVM, where a consumer mod's own tests run.
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
