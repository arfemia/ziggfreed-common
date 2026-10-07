package com.ziggfreed.common.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A data file the library moved to another folder: on the first load that finds it only in the old
 * folder it moves across with its {@code .bak}; a file in both folders keeps the new one and leaves
 * the old one alone; a move that fails leaves the file where it was and uses it there. The files are
 * written here with plain {@code Files.writeString}, never the engine's atomic writer, so every test
 * runs in the plain {@code test} JVM.
 */
class DataFileMoveTest {

    private static final String NAME = "totals.json";

    @TempDir
    Path dir;

    private Path home;
    private Path old;

    @BeforeEach
    void folders() throws IOException {
        home = dir.resolve("home");
        old = Files.createDirectories(dir.resolve("old"));
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    @Test
    void aFileOnlyInTheOldFolderMovesToTheNewOne() throws IOException {
        write(old.resolve(NAME), "old totals");

        DataFileMove.Home settled = DataFileMove.settle("test", home, old, NAME);

        assertEquals(home.resolve(NAME), settled.file(), "the file is kept in its new folder from now on");
        assertEquals(DataFileMove.Outcome.MOVED, settled.outcome());
        assertEquals("old totals", read(home.resolve(NAME)), "what the old folder held is what the new one holds");
        assertFalse(Files.exists(old.resolve(NAME)), "a move, not a copy: the old folder no longer holds it");
    }

    @Test
    void itsBackupMovesWithIt() throws IOException {
        write(old.resolve(NAME), "torn");
        write(old.resolve(NAME + ".bak"), "the write before");

        DataFileMove.Home settled = DataFileMove.settle("test", home, old, NAME);

        assertEquals(DataFileMove.Outcome.MOVED, settled.outcome());
        assertEquals("the write before", read(home.resolve(NAME + ".bak")), "the backup sits beside the file again");
        assertFalse(Files.exists(old.resolve(NAME + ".bak")));
    }

    @Test
    void aBackupTheNewFolderAlreadyHoldsIsNeverOverwritten() throws IOException {
        write(old.resolve(NAME), "old totals");
        write(old.resolve(NAME + ".bak"), "old backup");
        write(home.resolve(NAME + ".bak"), "new backup");

        DataFileMove.Home settled = DataFileMove.settle("test", home, old, NAME);

        assertEquals(home.resolve(NAME), settled.file(), "the file itself still moves");
        assertEquals("old totals", read(home.resolve(NAME)));
        assertEquals("new backup", read(home.resolve(NAME + ".bak")), "the new folder's backup is kept");
        assertEquals("old backup", read(old.resolve(NAME + ".bak")), "and the old one stays where it was");
    }

    @Test
    void whenBothFoldersHoldTheFileTheNewOneWinsAndTheOldIsLeftAlone() throws IOException {
        write(home.resolve(NAME), "new totals");
        write(old.resolve(NAME), "old totals");
        write(old.resolve(NAME + ".bak"), "old backup");

        DataFileMove.Home settled = DataFileMove.settle("test", home, old, NAME);

        assertEquals(home.resolve(NAME), settled.file());
        assertEquals(DataFileMove.Outcome.BOTH_FOUND, settled.outcome());
        assertEquals("new totals", read(home.resolve(NAME)));
        assertEquals("old totals", read(old.resolve(NAME)), "the old file is left untouched");
        assertEquals("old backup", read(old.resolve(NAME + ".bak")), "and so is its backup");
    }

    @Test
    void withNeitherFileTheNewFolderIsUsedAndNothingIsCreated() {
        DataFileMove.Home settled = DataFileMove.settle("test", home, old, NAME);

        assertEquals(home.resolve(NAME), settled.file());
        assertEquals(DataFileMove.Outcome.STAYED, settled.outcome());
        assertFalse(Files.exists(home), "nothing to move creates no folder");
        assertFalse(Files.exists(old.resolve(NAME)));
    }

    @Test
    void aFileAlreadyInTheNewFolderStaysThere() throws IOException {
        write(home.resolve(NAME), "new totals");

        DataFileMove.Home settled = DataFileMove.settle("test", home, old, NAME);

        assertEquals(home.resolve(NAME), settled.file());
        assertEquals(DataFileMove.Outcome.STAYED, settled.outcome());
    }

    @Test
    void noOldFolderOrTheSameFolderMovesNothing() throws IOException {
        write(home.resolve(NAME), "new totals");

        assertEquals(DataFileMove.Outcome.STAYED, DataFileMove.settle("test", home, null, NAME).outcome());
        DataFileMove.Home same = DataFileMove.settle("test", home, dir.resolve("old/../home"), NAME);
        assertEquals(DataFileMove.Outcome.STAYED, same.outcome(), "a folder spelled another way is the same folder");
        assertEquals("new totals", read(home.resolve(NAME)));
    }

    @Test
    void aMoveThatFailsLeavesTheFileWhereItWasAndUsesItThere() throws IOException {
        write(old.resolve(NAME), "old totals");
        write(old.resolve(NAME + ".bak"), "old backup");
        Path blocker = dir.resolve("blocker");
        Files.writeString(blocker, "a file where the new folder's parent should be", StandardCharsets.UTF_8);
        Path unreachable = blocker.resolve("home");

        DataFileMove.Home settled = assertDoesNotThrow(() -> DataFileMove.settle("test", unreachable, old, NAME),
                "a move that fails never reaches the caller");

        assertEquals(old.resolve(NAME), settled.file(), "the file is read, and kept, where it was this run");
        assertEquals(DataFileMove.Outcome.MOVE_FAILED, settled.outcome());
        assertEquals("old totals", read(old.resolve(NAME)), "nothing is lost");
        assertTrue(Files.exists(old.resolve(NAME + ".bak")), "the backup stays beside it");
    }
}
