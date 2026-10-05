package com.ziggfreed.common.entity.title;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The process-wide record of who shows what: lower-cased, per player, forgetting only a player who
 * shows nothing, and written to its file so a restart keeps every choice, online or not.
 *
 * <p>The tests that read back a file the record wrote go through the engine's own atomic writer,
 * which on Update 7 loads only under the engine's log manager, so they alone are tagged
 * {@code engine-items}. The read tests, and the guard that keeps a write the engine cannot make from
 * reaching a caller, run in the plain {@code test} JVM, where a consumer mod's own tests run.
 */
class ActiveTitlesTest {

    @TempDir
    Path dir;

    @AfterEach
    void clear() {
        ActiveTitles.persistTo(null, null);
        ActiveTitles.clear();
    }

    @Test
    void aShownTitleIsKeptLowerCasedPerPlayer() {
        UUID shower = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        ActiveTitles.put(shower, " Hallows_Eve_Hallowed ");

        assertEquals("hallows_eve_hallowed", ActiveTitles.of(shower));
        assertNull(ActiveTitles.of(other));
    }

    @Test
    void showingNothingForgetsThePlayer() {
        UUID player = UUID.randomUUID();
        ActiveTitles.put(player, "pumpkin_king");
        ActiveTitles.put(player, null);
        assertNull(ActiveTitles.of(player));

        ActiveTitles.put(player, "pumpkin_king");
        ActiveTitles.put(player, "  ");
        assertNull(ActiveTitles.of(player));
    }

    @Test
    void noPlayerReadsNone() {
        assertNull(ActiveTitles.of(null));
    }

    /**
     * Tagged {@code engine-items}: the write goes through the engine's own atomic writer
     * ({@code FileUtil.writeStringAtomic}), and on Update 7 that class logs at class init, which only
     * the engine's log manager allows ({@code engineItemTest}). In the plain {@code test} JVM the write
     * cannot happen at all; the guard is pinned untagged below.
     */
    @Tag("engine-items")
    @Test
    void theRecordSurvivesASaveAndARestart() {
        Path file = dir.resolve("shown-titles.json");
        UUID shower = UUID.randomUUID();
        UUID clearer = UUID.randomUUID();
        ActiveTitles.persistTo(file, null);
        ActiveTitles.put(shower, "Hallows_Eve_Hallowed");
        ActiveTitles.put(clearer, "pumpkin_king");
        ActiveTitles.put(clearer, null);
        ActiveTitles.flush();

        restart(file);

        assertEquals("hallows_eve_hallowed", ActiveTitles.of(shower));
        assertNull(ActiveTitles.of(clearer), "a cleared choice is not written back");
    }

    /** Tagged {@code engine-items}: it reads back what the flusher wrote, as the test above does. */
    @Tag("engine-items")
    @Test
    void aChangeIsHandedToTheFlusherOnceAndWrittenWhenItRuns() {
        Path file = dir.resolve("shown-titles.json");
        List<Runnable> queued = new ArrayList<>();
        ActiveTitles.persistTo(file, queued::add);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        ActiveTitles.put(first, "pumpkin_king");
        ActiveTitles.put(second, "hallows_eve_hallowed");
        assertEquals(1, queued.size(), "changes waiting on one flush share it");
        assertFalse(Files.exists(file), "nothing is written on the caller's thread");

        queued.remove(0).run();
        ActiveTitles.put(first, "Pumpkin_King");
        assertTrue(queued.isEmpty(), "a write that changes nothing asks for no flush");

        ActiveTitles.put(first, null);
        assertEquals(1, queued.size(), "the next change asks again");
        queued.remove(0).run();

        restart(file);
        assertNull(ActiveTitles.of(first));
        assertEquals("hallows_eve_hallowed", ActiveTitles.of(second));
    }

    @Test
    void aSavedIdReadsUnderEitherCasingAndABadRowIsSkipped() throws IOException {
        Path file = dir.resolve("shown-titles.json");
        UUID player = UUID.randomUUID();
        UUID blank = UUID.randomUUID();
        Files.writeString(file, "{ \"shownTitles\": { \""
                + player.toString().toUpperCase(Locale.ROOT) + "\": \" Hallows_Eve_Hallowed \", "
                + "\"not-a-player\": \"pumpkin_king\", \""
                + blank + "\": \"  \" } }");

        ActiveTitles.persistTo(file, null);

        assertEquals("hallows_eve_hallowed", ActiveTitles.of(player));
        assertNull(ActiveTitles.of(blank), "a blank row reads as showing nothing");
    }

    /** Tagged {@code engine-items}: the copy it falls back to is the one the engine's atomic write kept. */
    @Tag("engine-items")
    @Test
    void anUnreadableFileFallsBackToTheCopyBeforeIt() throws IOException {
        Path file = dir.resolve("shown-titles.json");
        UUID player = UUID.randomUUID();
        ActiveTitles.persistTo(file, null);
        ActiveTitles.put(player, "pumpkin_king");
        ActiveTitles.flush();
        ActiveTitles.put(player, "hallows_eve_hallowed");
        ActiveTitles.flush();
        Files.writeString(file, "{ torn");

        restart(file);

        assertEquals("pumpkin_king", ActiveTitles.of(player), "a torn file reads the copy written before it");
    }

    /**
     * The record in a JVM whose engine writer cannot run (a consumer mod's unit tests, or this module's
     * plain {@code test} task on Update 7): a change, a flush on the caller's thread and one handed to
     * the flusher never throw, and the record answers from memory, whatever the write does. Untagged
     * on purpose: it is the guard, and only the plain JVM proves it.
     */
    @Test
    void aWriteThatCannotHappenNeverReachesTheCaller() {
        Path file = dir.resolve("shown-titles.json");
        List<Runnable> queued = new ArrayList<>();
        ActiveTitles.persistTo(file, queued::add);
        UUID player = UUID.randomUUID();

        assertDoesNotThrow(() -> ActiveTitles.put(player, "pumpkin_king"));
        assertEquals(1, queued.size(), "the change is handed to the flusher");
        assertDoesNotThrow(() -> queued.remove(0).run());
        assertDoesNotThrow(ActiveTitles::flush);

        assertEquals("pumpkin_king", ActiveTitles.of(player), "the record answers from memory");
    }

    /** What a server restart leaves: nothing in memory, then whatever the file holds. */
    private static void restart(Path file) {
        ActiveTitles.clear();
        ActiveTitles.persistTo(file, null);
    }
}
