package com.ziggfreed.common.objectives.title;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.i18n.LangCatalog;
import com.ziggfreed.common.i18n.PlainText;

/**
 * What a title is called and where it sits around a player's name, on every surface at once: the
 * name ladder (authored key, convention key, typed name, the id spelled out), the flavor, the
 * display line with its shared fallback, and the reward chip.
 */
class TitleTextTest {

    static final Path ENGLISH = Path.of("src", "main", "resources", "Server", "Languages", "en-US",
            "ziggfreedcommon.title.lang");

    @AfterEach
    void clear() {
        LangCatalog.overrideForTests(null);
        TitleConfig.getInstance().mergePackLayer(Map.of());
    }

    @Test
    void aTitleNothingNamesReadsAsItsIdSpelledOut() {
        Message name = TitleText.nameOf("hallows_eve_hallowed");

        assertEquals("Hallows Eve Hallowed", name.getRawText(), "a traceable fallback, never a raw key");
        assertNull(name.getMessageId());
    }

    @Test
    void theConventionKeyNamesATitleUnderWhicheverNamespaceShipsIt() {
        LangCatalog.overrideForTests(Map.of("hallowseve.title.hallows_eve_hallowed.name", "The Hallowed"));

        assertEquals("hallowseve.title.hallows_eve_hallowed.name",
                TitleText.nameOf("Hallows_Eve_Hallowed").getMessageId());
    }

    @Test
    void anAuthoredTitleKeyWinsAndATypedNameIsTheLastWordBeforeTheId() throws Exception {
        TitleConfig.getInstance().mergePackLayer(Map.of(
                "Hallows_Eve_Hallowed", TitleAssetTest.title(
                        "{ \"Text\": { \"TitleKey\": \"ev.hallowed\" } }", "Hallows_Eve_Hallowed"),
                "Pumpkin_King", TitleAssetTest.title(
                        "{ \"Text\": { \"DisplayName\": \"Pumpkin King\" } }", "Pumpkin_King")));
        LangCatalog.overrideForTests(Map.of(
                "hallowseve.ev.hallowed", "The Hallowed",
                "hallowseve.title.hallows_eve_hallowed.name", "Convention"));

        assertEquals("hallowseve.ev.hallowed", TitleText.nameOf("hallows_eve_hallowed").getMessageId());
        assertEquals("Pumpkin King", TitleText.nameOf("pumpkin_king").getRawText());
    }

    @Test
    void theFlavorIsTheAuthoredOrConventionLineElseNothing() {
        assertNull(TitleText.flavorOf("hallows_eve_hallowed"));

        LangCatalog.overrideForTests(Map.of("hallowseve.title.hallows_eve_hallowed.flavor", "Earned in the fall."));
        assertEquals("hallowseve.title.hallows_eve_hallowed.flavor",
                TitleText.flavorOf("hallows_eve_hallowed").getMessageId());
    }

    @Test
    void aTitleWithItsOwnDisplayLinePlacesThePlayersNameItself() {
        LangCatalog.overrideForTests(Map.of("hallowseve.title.hallows_eve_hallowed.display", "{0} the Hallowed"));

        assertEquals("Ziggfreed the Hallowed",
                PlainText.of(TitleText.display("Hallows_Eve_Hallowed", null, "Ziggfreed")));
    }

    @Test
    void aTitleWithoutOneFollowsTheNameThroughTheSharedLine() {
        LangCatalog.overrideForTests(Map.of(
                "ziggfreedcommon.title.display", "{0}, {1}",
                "hallowseve.title.pumpkin_king.name", "Pumpkin King"));

        assertEquals("Ziggfreed, Pumpkin King", PlainText.of(TitleText.display("pumpkin_king", null, "Ziggfreed")));
    }

    @Test
    void theChipNamesTheTitle() {
        LangCatalog.overrideForTests(Map.of(
                "ziggfreedcommon.title.chip", "Title: {0}",
                "hallowseve.title.pumpkin_king.name", "Pumpkin King"));

        assertEquals("Title: Pumpkin King", PlainText.of(TitleText.chip("Pumpkin_King")));
    }

    @Test
    void theSharedLinesShipInTheEnglishFileUnderItsOwnPrefix() throws IOException {
        Map<String, String> english = shipped(ENGLISH);
        String fileName = ENGLISH.getFileName().toString();

        assertEquals(fileName.substring(0, fileName.length() - ".lang".length()) + ".", TitleText.PREFIX);
        assertTrue(english.get("display").contains("{0}") && english.get("display").contains("{1}"),
                "the shared display line has a blank for the player's name and one for the title's");
        assertTrue(english.get("chip").contains("{0}"), "the chip names the title");
    }

    /** A shipped lang file as key to value, comments and blanks skipped. */
    static Map<String, String> shipped(Path langFile) throws IOException {
        assertTrue(Files.isRegularFile(langFile), "missing " + langFile.toAbsolutePath());
        Map<String, String> entries = new LinkedHashMap<>();
        for (String line : Files.readAllLines(langFile, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            int eq = trimmed.indexOf('=');
            if (trimmed.isEmpty() || trimmed.startsWith("#") || eq <= 0) {
                continue;
            }
            entries.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
        }
        return entries;
    }
}
