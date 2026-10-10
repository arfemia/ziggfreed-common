package com.ziggfreed.common.entity.title;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ziggfreed.common.entity.title.PendingTitles.Change;
import com.ziggfreed.common.entity.title.PendingTitles.Kind;

/**
 * The queue of title changes owed to offline players: kept in order per player, written to its file
 * so a restart keeps it, and emptied for a player once paid.
 *
 * <p>The round trip reads back a file the engine's own atomic writer wrote, which on Update 7 loads
 * only under the engine's log manager, so it alone is tagged {@code engine-items}
 * ({@code entity/title/CLAUDE.md}).
 */
class PendingTitlesTest {

    @TempDir
    Path dir;

    @AfterEach
    void clear() {
        PendingTitles.persistTo(null, null);
        PendingTitles.clear();
    }

    /** Tagged {@code engine-items}: it reads back what the engine's atomic writer wrote. */
    @Tag("engine-items")
    @Test
    void theQueueSurvivesASaveAndARestartInOrderAndAPaidPlayerIsGone() {
        Path file = dir.resolve("pending-titles.json");
        UUID waiting = UUID.randomUUID();
        UUID paid = UUID.randomUUID();
        PendingTitles.persistTo(file, null);
        PendingTitles.add(waiting, Kind.GRANT, "Pumpkin_King");
        PendingTitles.add(waiting, Kind.REVOKE, "hallows_eve_hallowed");
        PendingTitles.add(waiting, Kind.GRANT, "hallows_eve_hallowed");
        PendingTitles.add(paid, Kind.GRANT, "pumpkin_king");
        PendingTitles.drain(paid);
        PendingTitles.flush();

        PendingTitles.clear();
        PendingTitles.persistTo(file, null);

        assertEquals(List.of(new Change(Kind.GRANT, "pumpkin_king"), new Change(Kind.REVOKE, "hallows_eve_hallowed"),
                new Change(Kind.GRANT, "hallows_eve_hallowed")), PendingTitles.of(waiting));
        assertFalse(PendingTitles.has(paid), "a drained queue is not written back");
    }

    @Test
    void aSavedQueueReadsInOrderAndABadRowIsSkipped() throws IOException {
        Path file = dir.resolve("pending-titles.json");
        UUID player = UUID.randomUUID();
        Files.writeString(file, "{ \"pending\": { \"" + player + "\": ["
                + "{ \"op\": \"grant\", \"title\": \" Pumpkin_King \" },"
                + "{ \"op\": \"melt\", \"title\": \"pumpkin_king\" },"
                + "{ \"op\": \"revoke\", \"title\": \"bad|id\" },"
                + "{ \"op\": \"REVOKE\", \"title\": \"pumpkin_king\" } ],"
                + "\"not-a-player\": [ { \"op\": \"grant\", \"title\": \"pumpkin_king\" } ] } }");

        PendingTitles.persistTo(file, null);

        assertEquals(List.of(new Change(Kind.GRANT, "pumpkin_king"), new Change(Kind.REVOKE, "pumpkin_king")),
                PendingTitles.drain(player));
        assertTrue(PendingTitles.of(player).isEmpty(), "draining takes everything");
    }
}
