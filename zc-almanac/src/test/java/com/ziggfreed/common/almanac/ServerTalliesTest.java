package com.ziggfreed.common.almanac;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The server's own totals per tally: what every player together counted. Counts only (no player is
 * named in the file), persisted the leaderboard way: a debounced atomic write with a {@code .bak}, and
 * whatever is still waiting written when the server stops. The file lives beside the owner files and
 * moves there from the library's data folder on the first load that finds it only in the old one.
 *
 * <p>The tests that read back a file the totals wrote go through the engine's own atomic writer
 * ({@code FileUtil.writeStringAtomic}), which on Update 7 loads only under the engine's log manager, so
 * they alone are tagged {@code engine-items} (as zc-loot's {@code PendingRewardStoreTest} and zc-entity's
 * {@code ActiveTitlesTest} are there). The read tests, whose files are written here with plain
 * {@code Files.writeString}, and the guard that keeps a write the engine cannot make from reaching a
 * caller, run in the plain {@code test} JVM, where a consumer mod's own tests run.
 */
class ServerTalliesTest {

    private static final String BOMBS = AlmanacKeys.lifetime("test_season", "bombs_thrown");
    private static final String BOMBS_2026 = AlmanacKeys.season("test_season", 2026, "bombs_thrown");

    @TempDir
    Path dir;

    @AfterEach
    void reset() {
        AlmanacSwitch.resetForTests();
    }

    /** Totals whose flush runs at once, so a test can read the file straight after a count. */
    private static ServerTallies immediate() {
        return new ServerTallies((task, delayMs) -> task.run());
    }

    @Test
    void aTotalAddsAndNeverGoesDown() {
        ServerTallies totals = immediate();
        totals.init(null);

        totals.add(BOMBS, 3L);
        totals.add(BOMBS, 2L);
        totals.add(BOMBS, 0L);
        totals.add(BOMBS, -4L);
        totals.add(" ", 9L);

        assertEquals(5L, totals.get(BOMBS));
        assertEquals(0L, totals.get(BOMBS_2026), "a key nothing counted reads 0");
        assertEquals(5L, totals.get(BOMBS.toUpperCase(Locale.ROOT)), "a key reads under any casing");
    }

    /** A file whose text is the shape the totals write: a version and the totals by key. */
    private static void writeTotals(Path file, String key, long total) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{ \"version\": 1, \"totals\": { \"" + key + "\": " + total + " } }",
                StandardCharsets.UTF_8);
    }

    /** Totals whose debounced write is queued and never run, as when the server stops before it fires. */
    private static ServerTallies queued(List<Runnable> waiting) {
        return new ServerTallies((task, delayMs) -> waiting.add(task));
    }

    /** Tagged {@code engine-items}: it reads back a file the engine's atomic writer wrote. */
    @Tag("engine-items")
    @Test
    void theTotalsSurviveAReloadOfTheirFile() {
        ServerTallies before = immediate();
        before.init(dir);
        before.add(BOMBS, 214L);
        before.add(BOMBS_2026, 57L);

        assertTrue(Files.isRegularFile(dir.resolve(ServerTallies.FILE_NAME)), "the totals are written to the data folder");

        ServerTallies after = immediate();
        after.init(dir);
        assertEquals(214L, after.get(BOMBS));
        assertEquals(57L, after.get(BOMBS_2026));
    }

    /** Tagged {@code engine-items}: it reads back a file the engine's atomic writer wrote. */
    @Tag("engine-items")
    @Test
    void theFileHoldsCountsAndNoPlayer() throws IOException {
        ServerTallies totals = immediate();
        totals.init(dir);
        totals.add(BOMBS, 1L);

        String written = Files.readString(dir.resolve(ServerTallies.FILE_NAME), StandardCharsets.UTF_8);
        assertTrue(written.contains(BOMBS));
        assertFalse(written.matches("(?s).*[0-9a-f]{8}-[0-9a-f]{4}-.*"), "no player id is ever written");
    }

    /** Tagged {@code engine-items}: the backup it reads is the one the engine's atomic writer kept. */
    @Tag("engine-items")
    @Test
    void aCorruptFileFallsBackToItsBackup() throws IOException {
        ServerTallies totals = immediate();
        totals.init(dir);
        totals.add(BOMBS, 1L);
        totals.add(BOMBS, 1L);
        Files.writeString(dir.resolve(ServerTallies.FILE_NAME), "{ not json", StandardCharsets.UTF_8);

        ServerTallies reloaded = immediate();
        reloaded.init(dir);

        assertEquals(1L, reloaded.get(BOMBS), "the write before the last is the backup, and it is read instead");
    }

    @Test
    void aCorruptFileWithNoBackupStartsEmptyAndKeepsCounting() throws IOException {
        Files.writeString(dir.resolve(ServerTallies.FILE_NAME), "{ not json", StandardCharsets.UTF_8);

        ServerTallies totals = immediate();
        totals.init(dir);
        totals.add(BOMBS, 2L);

        assertEquals(2L, totals.get(BOMBS));
    }

    @Test
    void nothingIsCountedWhileTheAlmanacIsOff() {
        ServerTallies totals = immediate();
        totals.init(dir);

        AlmanacSwitch.set(false);
        totals.add(BOMBS, 5L);
        totals.addGrowth(Map.of(), Map.of(BOMBS, 5L));

        assertEquals(0L, totals.get(BOMBS));
        assertFalse(Files.exists(dir.resolve(ServerTallies.FILE_NAME)), "and nothing is written");
    }

    @Test
    void growthAddsOnlyWhatRoseBetweenTwoReadings() {
        ServerTallies totals = immediate();
        totals.init(null);

        totals.addGrowth(Map.of("a/x", 1L, "a/y", 2L), Map.of("a/x", 1L, "a/y", 5L, "a/z", 1L));

        assertEquals(0L, totals.get("a/x"), "unchanged adds nothing");
        assertEquals(3L, totals.get("a/y"));
        assertEquals(1L, totals.get("a/z"), "a key that appeared adds its whole value");
    }

    @Test
    void withNoDataFolderItCountsAndNeverWrites() {
        ServerTallies totals = immediate();
        totals.init(null);
        totals.add(BOMBS, 4L);
        totals.flushNow();

        assertEquals(4L, totals.get(BOMBS));
    }

    @Test
    void writesAreDebouncedIntoOne() {
        int[] scheduled = {0};
        ServerTallies totals = new ServerTallies((task, delayMs) -> scheduled[0]++);
        totals.init(dir);

        totals.add(BOMBS, 1L);
        totals.add(BOMBS, 1L);
        totals.add(BOMBS_2026, 1L);

        assertEquals(1, scheduled[0], "counts landing before the flush ride one write");
    }

    // ==================== the write when the server stops ====================

    /**
     * Tagged {@code engine-items}: it reads back what the stop wrote. A count whose debounced write never
     * ran (the server stopped first) is on disk once the stop's write returns; the stop's write, the late
     * debounced one and a second stop together write the file once, since nothing new means no write.
     */
    @Tag("engine-items")
    @Test
    void aCountStillWaitingIsWrittenWhenTheServerStopsAndOnlyOnce() {
        List<Runnable> waiting = new ArrayList<>();
        ServerTallies totals = queued(waiting);
        totals.init(dir);
        totals.add(BOMBS, 6L);
        assertFalse(Files.exists(dir.resolve(ServerTallies.FILE_NAME)), "the debounced write has not run yet");

        totals.flushNow();

        ServerTallies reloaded = immediate();
        reloaded.init(dir);
        assertEquals(6L, reloaded.get(BOMBS), "the stop wrote the count the debounce was still holding");

        assertEquals(1, waiting.size());
        waiting.remove(0).run();
        totals.flushNow();
        assertFalse(Files.exists(dir.resolve(ServerTallies.FILE_NAME + ".bak")),
                "the late debounced write and a second stop find nothing new, so the file is written once");
    }

    @Test
    void aStopWithNothingCountedWritesNothing() {
        ServerTallies totals = queued(new ArrayList<>());
        totals.init(dir);

        totals.flushNow();

        assertFalse(Files.exists(dir.resolve(ServerTallies.FILE_NAME)), "a stop with nothing new leaves no file");
    }

    /**
     * The totals in a JVM whose engine writer cannot run (a consumer mod's unit tests, or this module's plain
     * {@code test} task on Update 7), or over a folder the server cannot write: a count, its debounced write
     * and the stop's write never throw, and the totals answer from memory whatever the write does. Untagged
     * on purpose: it is the guard, and only the plain JVM proves it.
     */
    @Test
    void aWriteThatCannotHappenNeverReachesTheCaller() throws IOException {
        Path blocker = dir.resolve("blocker");
        Files.writeString(blocker, "a file where the folder should be", StandardCharsets.UTF_8);
        List<Runnable> waiting = new ArrayList<>();
        ServerTallies totals = queued(waiting);
        totals.init(blocker.resolve("data"));

        assertDoesNotThrow(() -> totals.add(BOMBS, 3L));
        assertDoesNotThrow(() -> waiting.remove(0).run());
        assertDoesNotThrow(totals::flushNow);
        assertDoesNotThrow(totals::flushNow);

        assertEquals(3L, totals.get(BOMBS), "the totals answer from memory");
    }

    /**
     * Tagged {@code engine-items}: it reads back the retried write. A write that fails keeps the totals'
     * change, so the next write (here the stop's, once the folder can be made) carries it.
     */
    @Tag("engine-items")
    @Test
    void aFailedWriteIsTriedAgainByTheNext() throws IOException {
        Path blocker = dir.resolve("blocker");
        Files.writeString(blocker, "a file where the folder should be", StandardCharsets.UTF_8);
        Path folder = blocker.resolve("data");
        List<Runnable> waiting = new ArrayList<>();
        ServerTallies totals = queued(waiting);
        totals.init(folder);
        totals.add(BOMBS, 8L);

        waiting.remove(0).run();
        assertFalse(Files.exists(folder.resolve(ServerTallies.FILE_NAME)), "the folder cannot be made, so the write fails");

        Files.delete(blocker);
        totals.flushNow();

        ServerTallies reloaded = immediate();
        reloaded.init(folder);
        assertEquals(8L, reloaded.get(BOMBS), "the failed write's count is written by the next write");
    }

    // ==================== the move to the owner folder ====================

    @Test
    void totalsOnlyInTheOldFolderMoveToTheNewOneAndAreRead() throws IOException {
        Path home = dir.resolve("home");
        Path old = dir.resolve("old");
        writeTotals(old.resolve(ServerTallies.FILE_NAME), BOMBS, 41L);

        ServerTallies totals = immediate();
        totals.init(home, old);

        assertEquals(41L, totals.get(BOMBS), "an earlier run's totals are read from their new folder");
        assertTrue(Files.isRegularFile(home.resolve(ServerTallies.FILE_NAME)), "the file now sits beside the owner files");
        assertFalse(Files.exists(old.resolve(ServerTallies.FILE_NAME)), "and the old folder no longer holds it");
    }

    @Test
    void aTornFileMovesWithItsBackupAndTheBackupIsRead() throws IOException {
        Path home = dir.resolve("home");
        Path old = Files.createDirectories(dir.resolve("old"));
        Files.writeString(old.resolve(ServerTallies.FILE_NAME), "{ not json", StandardCharsets.UTF_8);
        writeTotals(old.resolve(ServerTallies.FILE_NAME + ".bak"), BOMBS, 12L);

        ServerTallies totals = immediate();
        totals.init(home, old);

        assertEquals(12L, totals.get(BOMBS), "the backup moved with the file, so a torn file still reads it");
        assertTrue(Files.isRegularFile(home.resolve(ServerTallies.FILE_NAME + ".bak")));
        assertFalse(Files.exists(old.resolve(ServerTallies.FILE_NAME + ".bak")));
    }

    @Test
    void whenBothFoldersHoldTotalsTheNewOnesAreReadAndTheOldAreLeftAlone() throws IOException {
        Path home = dir.resolve("home");
        Path old = dir.resolve("old");
        writeTotals(home.resolve(ServerTallies.FILE_NAME), BOMBS, 90L);
        writeTotals(old.resolve(ServerTallies.FILE_NAME), BOMBS, 5L);
        String oldText = Files.readString(old.resolve(ServerTallies.FILE_NAME), StandardCharsets.UTF_8);

        ServerTallies totals = immediate();
        totals.init(home, old);

        assertEquals(90L, totals.get(BOMBS), "the new folder's totals win");
        assertEquals(oldText, Files.readString(old.resolve(ServerTallies.FILE_NAME), StandardCharsets.UTF_8),
                "the old file is left untouched");
    }

    @Test
    void withNeitherFileTheTotalsStartEmpty() {
        Path home = dir.resolve("home");
        Path old = dir.resolve("old");

        ServerTallies totals = immediate();
        totals.init(home, old);

        assertEquals(0L, totals.get(BOMBS));
        assertFalse(Files.exists(home.resolve(ServerTallies.FILE_NAME)));
        assertFalse(Files.exists(old.resolve(ServerTallies.FILE_NAME)));
    }

    @Test
    void aMoveThatFailsReadsTheOldFile() throws IOException {
        Path blocker = dir.resolve("blocker");
        Files.writeString(blocker, "a file where the new folder's parent should be", StandardCharsets.UTF_8);
        Path old = dir.resolve("old");
        writeTotals(old.resolve(ServerTallies.FILE_NAME), BOMBS, 33L);

        ServerTallies totals = immediate();
        assertDoesNotThrow(() -> totals.init(blocker.resolve("home"), old), "a failed move never fails the load");

        assertEquals(33L, totals.get(BOMBS), "the totals are read where they were");
        assertTrue(Files.isRegularFile(old.resolve(ServerTallies.FILE_NAME)));
    }

    /** Tagged {@code engine-items}: it reads back a file the engine's atomic writer wrote. */
    @Tag("engine-items")
    @Test
    void aMoveThatFailsKeepsWritingTheOldFileThisRun() throws IOException {
        Path blocker = dir.resolve("blocker");
        Files.writeString(blocker, "a file where the new folder's parent should be", StandardCharsets.UTF_8);
        Path old = dir.resolve("old");
        writeTotals(old.resolve(ServerTallies.FILE_NAME), BOMBS, 33L);

        ServerTallies totals = immediate();
        totals.init(blocker.resolve("home"), old);
        totals.add(BOMBS, 2L);

        ServerTallies reloaded = immediate();
        reloaded.init(old);
        assertEquals(35L, reloaded.get(BOMBS), "the count is saved where the file stayed, so nothing is lost");
    }
}
