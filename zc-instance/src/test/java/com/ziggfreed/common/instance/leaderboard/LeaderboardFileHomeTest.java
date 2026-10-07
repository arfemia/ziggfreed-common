package com.ziggfreed.common.instance.leaderboard;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A board that moved folders (the encounter board, from the library's data folder to the owner folder):
 * on the first load that finds its file only in the old folder it moves across with its {@code .bak} and
 * reads; a file in both keeps the new one and leaves the old alone; neither starts empty; a move that
 * fails reads the file where it was. The files are written here with plain {@code Files.writeString},
 * never the engine's atomic writer, so every test runs in the plain {@code test} JVM.
 */
class LeaderboardFileHomeTest {

    private static final String FILE = "boss-board.json";
    private static final UUID ADA = new UUID(0, 1);

    @TempDir
    Path dir;

    /** A board file in the shape the board writes, holding one row with {@code bestScore}. */
    private static void writeBoard(Path file, int bestScore) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{ \"buckets\": { \"bosses\": { \"" + ADA + "\": { \"bestScore\": " + bestScore
                + ", \"plays\": 1, \"name\": \"Ada\" } } } }", StandardCharsets.UTF_8);
    }

    private static int bestScore(Leaderboard board) {
        LeaderboardEntry entry = board.forBucket("bosses").get(ADA);
        return entry == null ? -1 : entry.bestScore;
    }

    @Test
    void aBoardOnlyInTheOldFolderMovesToTheNewOneAndIsRead() throws IOException {
        Path home = dir.resolve("home");
        Path old = dir.resolve("old");
        writeBoard(old.resolve(FILE), 88);

        Leaderboard board = new Leaderboard("boss-board");
        board.init(home, old);

        assertEquals(88, bestScore(board), "an earlier run's rows are read from their new folder");
        assertTrue(Files.isRegularFile(home.resolve(FILE)));
        assertFalse(Files.exists(old.resolve(FILE)), "a move, not a copy");
    }

    @Test
    void aTornBoardMovesWithItsBackupAndTheBackupIsRead() throws IOException {
        Path home = dir.resolve("home");
        Path old = Files.createDirectories(dir.resolve("old"));
        Files.writeString(old.resolve(FILE), "{ not json", StandardCharsets.UTF_8);
        writeBoard(old.resolve(FILE + ".bak"), 61);

        Leaderboard board = new Leaderboard("boss-board");
        board.init(home, old);

        assertEquals(61, bestScore(board), "the backup moved with the file, so a torn file still reads it");
        assertTrue(Files.isRegularFile(home.resolve(FILE + ".bak")));
        assertFalse(Files.exists(old.resolve(FILE + ".bak")));
    }

    @Test
    void whenBothFoldersHoldTheBoardTheNewOneIsReadAndTheOldIsLeftAlone() throws IOException {
        Path home = dir.resolve("home");
        Path old = dir.resolve("old");
        writeBoard(home.resolve(FILE), 90);
        writeBoard(old.resolve(FILE), 5);
        String oldText = Files.readString(old.resolve(FILE), StandardCharsets.UTF_8);

        Leaderboard board = new Leaderboard("boss-board");
        board.init(home, old);

        assertEquals(90, bestScore(board), "the new folder's board wins");
        assertEquals(oldText, Files.readString(old.resolve(FILE), StandardCharsets.UTF_8), "the old file is untouched");
    }

    @Test
    void withNeitherFileTheBoardStartsEmpty() {
        Path home = dir.resolve("home");
        Path old = dir.resolve("old");

        Leaderboard board = new Leaderboard("boss-board");
        board.init(home, old);

        assertTrue(board.bucketKeys().isEmpty());
        assertFalse(Files.exists(home.resolve(FILE)));
    }

    @Test
    void aMoveThatFailsReadsTheOldFile() throws IOException {
        Path blocker = dir.resolve("blocker");
        Files.writeString(blocker, "a file where the new folder's parent should be", StandardCharsets.UTF_8);
        Path old = dir.resolve("old");
        writeBoard(old.resolve(FILE), 47);

        Leaderboard board = new Leaderboard("boss-board");
        assertDoesNotThrow(() -> board.init(blocker.resolve("home"), old), "a failed move never fails the load");

        assertEquals(47, bestScore(board), "the rows are read where they were");
        assertTrue(Files.isRegularFile(old.resolve(FILE)));
    }
}
