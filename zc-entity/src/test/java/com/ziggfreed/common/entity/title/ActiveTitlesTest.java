package com.ziggfreed.common.entity.title;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The process-wide record of who shows what: lower-cased, per player, forgetting only a player who
 * shows nothing, and written to its file so a restart keeps every choice, online or not.
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

    /** What a server restart leaves: nothing in memory, then whatever the file holds. */
    private static void restart(Path file) {
        ActiveTitles.clear();
        ActiveTitles.persistTo(file, null);
    }
}
