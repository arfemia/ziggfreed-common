package com.ziggfreed.common.instance.leaderboard;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A board's write when the server stops: a row whose debounced write never ran is on disk once the
 * stop's write returns; the stop's write, a late debounced write and a second stop write the file once;
 * a stop with nothing new writes nothing; a write that fails is tried again by the next; and none of it
 * ever throws. The encounter board is written through its listener, which the library's stop calls.
 *
 * <p>The tests that read back a file the board wrote go through the engine's own atomic writer
 * ({@code FileUtil.writeStringAtomic}), which on Update 7 loads only under the engine's log manager, so
 * they alone are tagged {@code engine-items}. The guard that keeps a write the engine cannot make from
 * reaching a caller runs untagged, in the plain {@code test} JVM, where a consumer mod's own tests run.
 */
class LeaderboardStopTest {

    private static final String NAME = "boss-board";
    private static final UUID ADA = new UUID(0, 1);

    @TempDir
    Path dir;

    @AfterEach
    void reset() {
        EncounterLeaderboardListener.installForTests(null);
    }

    /** A board whose debounced write is queued and never run, as when the server stops before it fires. */
    private static Leaderboard queued(List<Runnable> waiting) {
        return new Leaderboard(NAME, waiting::add);
    }

    private static void defeat(Leaderboard board, int score) {
        board.record("bosses", ADA, "Ada", score, 90, true);
    }

    /** The row a fresh load of {@code folder} reads back, or null. */
    private static LeaderboardEntry reread(Path folder) {
        Leaderboard reloaded = new Leaderboard(NAME, Runnable::run);
        reloaded.init(folder);
        return reloaded.forBucket("bosses").get(ADA);
    }

    private Path blocker() throws IOException {
        Path blocker = dir.resolve("blocker");
        Files.writeString(blocker, "a file where the board's folder should be", StandardCharsets.UTF_8);
        return blocker;
    }

    /** Tagged {@code engine-items}: it reads back what the stop wrote through the engine's atomic writer. */
    @Tag("engine-items")
    @Test
    void aRowStillWaitingIsWrittenWhenTheServerStopsAndOnlyOnce() {
        List<Runnable> waiting = new ArrayList<>();
        Leaderboard board = queued(waiting);
        board.init(dir);
        defeat(board, 70);
        assertFalse(Files.exists(dir.resolve(NAME + ".json")), "the debounced write has not run yet");

        board.flushNow();

        LeaderboardEntry row = reread(dir);
        assertNotNull(row, "the stop wrote the row the debounce was still holding");
        assertEquals(70, row.bestScore);

        assertEquals(1, waiting.size());
        waiting.remove(0).run();
        board.flushNow();
        assertFalse(Files.exists(dir.resolve(NAME + ".json.bak")),
                "the late debounced write and a second stop find nothing new, so the file is written once");
    }

    @Test
    void aStopWithNothingRecordedWritesNothing() {
        Leaderboard board = queued(new ArrayList<>());
        board.init(dir);

        board.flushNow();

        assertFalse(Files.exists(dir.resolve(NAME + ".json")), "a stop with nothing new leaves no file");
    }

    /**
     * Tagged {@code engine-items}: it reads back the retried write. A write that fails keeps the board's
     * change, so the next write (here the stop's, once the folder can be made) carries it.
     */
    @Tag("engine-items")
    @Test
    void aFailedWriteIsTriedAgainByTheNext() throws IOException {
        Path blocker = blocker();
        Path folder = blocker.resolve("data");
        List<Runnable> waiting = new ArrayList<>();
        Leaderboard board = queued(waiting);
        board.init(folder);
        defeat(board, 55);

        waiting.remove(0).run();
        assertFalse(Files.exists(folder.resolve(NAME + ".json")), "the folder cannot be made, so the write fails");

        Files.delete(blocker);
        board.flushNow();

        LeaderboardEntry row = reread(folder);
        assertNotNull(row, "the failed write's row is written by the next write");
        assertEquals(55, row.bestScore);
    }

    /** Tagged {@code engine-items}: it reads back what the listener's stop wrote. */
    @Tag("engine-items")
    @Test
    void theListenersStopWritesTheEncounterBoard() {
        Leaderboard board = queued(new ArrayList<>());
        board.init(dir);
        EncounterLeaderboardListener.installForTests(board);
        defeat(board, 40);

        EncounterLeaderboardListener.flushNow();

        LeaderboardEntry row = reread(dir);
        assertNotNull(row, "the installed board's waiting row is written at the stop");
        assertEquals(40, row.bestScore);
    }

    /**
     * The board in a JVM whose engine writer cannot run (a consumer mod's unit tests, or this module's plain
     * {@code test} task on Update 7), or over a folder the server cannot write: a record, its debounced write
     * and the stops never throw, and the board answers from memory whatever the write does. Untagged on
     * purpose: it is the guard, and only the plain JVM proves it.
     */
    @Test
    void aWriteThatCannotHappenNeverReachesTheCaller() throws IOException {
        List<Runnable> waiting = new ArrayList<>();
        Leaderboard board = queued(waiting);
        board.init(blocker().resolve("data"));
        EncounterLeaderboardListener.installForTests(board);

        assertDoesNotThrow(() -> defeat(board, 30));
        assertDoesNotThrow(() -> waiting.remove(0).run());
        assertDoesNotThrow(board::flushNow);
        assertDoesNotThrow(EncounterLeaderboardListener::flushNow);

        assertEquals(30, board.forBucket("bosses").get(ADA).bestScore, "the board answers from memory");
    }

    @Test
    void theListenersStopBeforeAnyInstallDoesNothing() {
        EncounterLeaderboardListener.installForTests(null);

        assertDoesNotThrow(EncounterLeaderboardListener::flushNow);
    }
}
