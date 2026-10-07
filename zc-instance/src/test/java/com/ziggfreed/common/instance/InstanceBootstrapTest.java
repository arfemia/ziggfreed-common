package com.ziggfreed.common.instance;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ziggfreed.common.instance.leaderboard.EncounterLeaderboardListener;
import com.ziggfreed.common.instance.leaderboard.Leaderboard;
import com.ziggfreed.common.instance.leaderboard.LeaderboardEntry;

/**
 * The instance module's stop, which the library's {@code shutdown()} calls: it writes the encounter
 * board's rows still waiting on their debounce, and it never throws, with or without a board.
 */
class InstanceBootstrapTest {

    private static final UUID ADA = new UUID(0, 1);

    @TempDir
    Path dir;

    @AfterEach
    void reset() {
        EncounterLeaderboardListener.installForTests(null);
    }

    /**
     * Tagged {@code engine-items}: it reads back what the stop wrote through the engine's own atomic
     * writer, which on Update 7 loads only under the engine's log manager.
     */
    @Tag("engine-items")
    @Test
    void theStopWritesTheEncounterBoardStillWaiting() {
        Leaderboard board = new Leaderboard(EncounterLeaderboardListener.BOARD, new ArrayList<Runnable>()::add);
        board.init(dir);
        EncounterLeaderboardListener.installForTests(board);
        board.record("bosses", ADA, "Ada", 64, 120, true);

        InstanceBootstrap.shutdown();

        Leaderboard reloaded = new Leaderboard(EncounterLeaderboardListener.BOARD, Runnable::run);
        reloaded.init(dir);
        LeaderboardEntry row = reloaded.forBucket("bosses").get(ADA);
        assertNotNull(row, "the stop wrote the defeat the debounce was still holding");
        assertEquals(64, row.bestScore);
    }

    /** Untagged on purpose: the stop never throws, whether or not a board was ever installed. */
    @Test
    void theStopNeverThrows() {
        EncounterLeaderboardListener.installForTests(null);

        assertDoesNotThrow(InstanceBootstrap::shutdown);
        assertDoesNotThrow(InstanceBootstrap::shutdown, "a second stop is as harmless as the first");
    }
}
