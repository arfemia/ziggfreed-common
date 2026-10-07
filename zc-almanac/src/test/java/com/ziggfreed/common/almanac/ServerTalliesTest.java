package com.ziggfreed.common.almanac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The server's own totals per tally: what every player together counted. Counts only (no player is
 * named in the file), persisted the leaderboard way: a debounced atomic write with a {@code .bak}.
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

    @Test
    void theFileHoldsCountsAndNoPlayer() throws IOException {
        ServerTallies totals = immediate();
        totals.init(dir);
        totals.add(BOMBS, 1L);

        String written = Files.readString(dir.resolve(ServerTallies.FILE_NAME), StandardCharsets.UTF_8);
        assertTrue(written.contains(BOMBS));
        assertFalse(written.matches("(?s).*[0-9a-f]{8}-[0-9a-f]{4}-.*"), "no player id is ever written");
    }

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
}
