package com.ziggfreed.common.achievement.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.i18n.LangCatalog;
import com.ziggfreed.common.progress.ContentText;

/**
 * A yearly copy may be named for its own year: achievement.<copy id>.title names the copy when a lang file
 * ships it, and a year with no line of its own reads the title every copy shares, with its year in the slot.
 * An ordinary achievement never asks for a year's line. Every catalogue is a fixture handed to LangCatalog.
 */
class OccurrenceOwnTitleTest {

    private static final String EVENT = "yourmod_festival";

    @AfterEach
    void realCatalogue() {
        LangCatalog.overrideForTests(null);
    }

    private static ContentText copyOf(AchievementAsset asset, int year) {
        return asset.toDefinition(new OccurrenceMinting.Mint("festival_anniversary_" + year, EVENT, year,
                "festival_anniversary", new FakeCalendar().reader(), UnaryOperator.identity())).achievement().text();
    }

    private static AchievementAsset festival(String text) throws IOException {
        return AchievementAssetCodecTest.decodeRoot("""
                { "Occurrence": { "Event": "YourMod_Festival" },
                  "Text": %s,
                  "Criteria": { "be-here": { "Kind": "CALENDAR_ATTENDED", "Target": "YourMod_Festival", "Amount": 1 } } }
                """.formatted(text), "festival_anniversary");
    }

    @Test
    void aCopyWhoseYearShipsALineOfItsOwnIsNamedByIt() throws IOException {
        LangCatalog.overrideForTests(Map.of(
                "yourmod.festival.anniversary.title", "Festival Anniversary {0}",
                "yourmod.achievement.festival_anniversary_2027.title", "The First Festival"));
        ContentText text = copyOf(festival(
                "{ \"TitleKey\": \"festival.anniversary.title\", \"TextArgs\": { \"Title\": [ \"@year\" ] } }"), 2027);

        assertEquals("yourmod.achievement.festival_anniversary_2027.title", text.title().getMessageId(),
                "the year's own line names its copy, ahead of the explicit title");
        assertEquals("achievement.festival_anniversary_2027.title", text.resolvableTitleKey(),
                "and a surface that carries keys hands that line over");
    }

    @Test
    void aCopyWhoseYearHasNoLineReadsTheSharedTitleWithItsYear() throws IOException {
        LangCatalog.overrideForTests(Map.of(
                "yourmod.festival.anniversary.title", "Festival Anniversary {0}",
                "yourmod.achievement.festival_anniversary_2027.title", "The First Festival"));
        Message title = copyOf(festival(
                "{ \"TitleKey\": \"festival.anniversary.title\", \"TextArgs\": { \"Title\": [ \"@year\" ] } }"), 2028)
                .title();

        assertEquals("yourmod.festival.anniversary.title", title.getMessageId(),
                "a year with no line of its own reads the title every copy shares");
        assertEquals("2028", title.getFormattedMessage().messageParams.get("0").rawText, "with its own year in the slot");
    }

    @Test
    void aCopyNamedByItsIdReadsItsYearsLineThenTheLineEveryCopyShares() throws IOException {
        LangCatalog.overrideForTests(Map.of(
                "yourmod.achievement.festival_anniversary.title", "Festival Anniversary {0}",
                "yourmod.achievement.festival_anniversary_2027.title", "The First Festival"));
        AchievementAsset asset = festival("{ \"TextArgs\": { \"Title\": [ \"@year\" ] } }");

        assertEquals("yourmod.achievement.festival_anniversary_2027.title", copyOf(asset, 2027).title().getMessageId(),
                "a file that writes no key still names a year by its own line");
        assertEquals("yourmod.achievement.festival_anniversary.title", copyOf(asset, 2028).title().getMessageId(),
                "and any other year by the convention line its base id spells");
    }

    @Test
    void anOrdinaryAchievementNeverAsksForAYearsLine() throws IOException {
        AchievementAsset plain = AchievementAssetCodecTest.decodeRoot("""
                { "Text": { "TitleKey": "festival.anniversary.title" },
                  "Criteria": { "be-here": { "Kind": "CALENDAR_ATTENDED", "Target": "YourMod_Festival", "Amount": 1 } } }
                """, "festival_anniversary");

        assertNull(plain.toDefinition().achievement().text().titleOwnKey(), "only a yearly copy has a year's line");
    }
}
