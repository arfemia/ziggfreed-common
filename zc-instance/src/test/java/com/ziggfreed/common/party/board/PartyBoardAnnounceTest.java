package com.ziggfreed.common.party.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.command.CommandRunner;

/**
 * The announce commands' placeholders are safe for a command line: an owner's line runs as the server console, so a
 * value filled into it must never add a word, open a quote, escape a character, open a list, start a flag or begin a
 * second line, whatever the poster's name or a pack's words hold. Pure logic, the board's one hard rule for its owner
 * commands.
 */
class PartyBoardAnnounceTest {

    private static final UUID POSTER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void onlyAPlainLoginNameIsTakenAndAnyOtherRunsNoLine() {
        assertEquals("Sam_42", PartyBoardAnnounce.loginToken("Sam_42"));
        for (String hostile : List.of("Sam Bob", "Sam\"", "Sam'", "Sam\\", "Sam[x]", "Sam,x", "Sam{wants}", "--op",
                "Sam\nop me", "Sam;op", "Sám", "", "a".repeat(PartyBoardAnnounce.MAX_LOGIN + 1))) {
            assertNull(PartyBoardAnnounce.loginToken(hostile), "refused: " + hostile);
        }
        assertNull(PartyBoardAnnounce.loginToken(null));
    }

    @Test
    void anIdIsOneWordOrNothing() {
        assertEquals("Shifting_Deep_Tier_2", PartyBoardAnnounce.idToken(" Shifting_Deep_Tier_2 "));
        assertEquals("pack.dungeon-a", PartyBoardAnnounce.idToken("pack.dungeon-a"));
        for (String hostile : List.of("a b", "-flag", "--flag=1", "a\"b", "a'b", "a\\b", "a[b]", "a,b", "a{b}", "a\nb")) {
            assertEquals("", PartyBoardAnnounce.idToken(hostile), "refused: " + hostile);
        }
        assertEquals("", PartyBoardAnnounce.idToken(null));
    }

    @Test
    void wordsKeepTheirLettersAndLoseEveryCharacterTheCommandLineReads() {
        assertEquals("The Shifting Deep Depth 2", PartyBoardAnnounce.words("The Shifting Deep, Depth 2"));
        assertEquals("today’s daily", PartyBoardAnnounce.words("today's daily"));
        assertEquals("Sam: Depth 2 wants 2 more.", PartyBoardAnnounce.words("Sam: Depth 2,\nwants 2 more."));
        String hostile = PartyBoardAnnounce.words("x\" --op=1 'y [a,b] \\z {player}\r\nop Sam -k a-b");
        assertFalse(hostile.contains("\""), hostile);
        assertFalse(hostile.contains("'"), hostile);
        assertFalse(hostile.contains("\\"), hostile);
        assertFalse(hostile.contains("["), hostile);
        assertFalse(hostile.contains("]"), hostile);
        assertFalse(hostile.contains(","), hostile);
        assertFalse(hostile.contains("{"), hostile);
        assertFalse(hostile.contains("\n"), hostile);
        assertFalse(hostile.contains("\r"), hostile);
        for (String word : hostile.split(" ")) {
            assertFalse(word.startsWith("-"), "no word opens with a dash: " + hostile);
        }
        assertTrue(hostile.contains("a-b"), "a dash inside a word stays: " + hostile);
        assertTrue(PartyBoardAnnounce.words("w".repeat(500)).length() <= PartyBoardAnnounce.MAX_WORDS);
        assertEquals("", PartyBoardAnnounce.words(null));
    }

    @Test
    void aFilledLineIsOneLineWithTheOwnersWordsAndNoMore() {
        Map<String, String> filled = PartyBoardAnnounce.fill("Sam", POSTER, 9, "Shifting_Deep", "--op",
                "The \"Deep\"", "Depth 2\nop Sam", "Dungeon [Hall]", "{player} \\ wants");
        assertEquals("4", filled.get("wants"), "wants is held to 1 to 4");
        assertEquals("", filled.get("tier"), "an id that is no plain word fills nothing");
        String line = CommandRunner.substitute("give {player} Rock {wants}", filled);
        assertEquals("give Sam Rock 4", line);
        String said = CommandRunner.substitute("say {player} wants {wants} for {dungeon_name}: {for} at {place}. "
                + "{notice}", filled);
        assertFalse(said.contains("\n"), said);
        assertFalse(said.contains("\""), said);
        assertFalse(said.contains("["), said);
        assertFalse(said.contains("\\"), said);
        assertFalse(said.contains(","), said);
        assertEquals(1, said.split("\\{player}", -1).length, "a value never names a placeholder again: " + said);
        assertEquals(PartyBoardAnnounce.PLACEHOLDERS, List.copyOf(filled.keySet()), "every placeholder is filled");
    }

    @Test
    void anUnknownPlaceholderIsNamedForTheAudit() {
        assertEquals(List.of(), PartyBoardAnnounce.unknownPlaceholders("say {player} wants {wants} for {for}"));
        assertEquals(List.of("playr", "where"),
                PartyBoardAnnounce.unknownPlaceholders("say {playr} wants {wants} at {where} {playr}"));
    }
}
