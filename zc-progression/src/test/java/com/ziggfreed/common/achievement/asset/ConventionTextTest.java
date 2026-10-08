package com.ziggfreed.common.achievement.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.i18n.LangCatalog;
import com.ziggfreed.common.progress.ContentText;
import com.ziggfreed.common.progress.ConventionKeys;
import com.ziggfreed.common.validation.Finding;

/**
 * An achievement that writes no key is named by convention, a yearly copy by its BASE id, an explicit
 * key that ships still wins, and a name that resolves nowhere is reported once per base.
 */
class ConventionTextTest {

    private static final String KEEPSAKE = """
            { "Occurrence": { "Event": "yourmod_festival" }, "Text": { "TextArgs": { "Title": [ "@year" ] } },
              "Criteria": { "one": { "Kind": "BREAK_BLOCK", "Amount": 1 } } }
            """;

    @AfterEach
    void realCatalogue() {
        LangCatalog.overrideForTests(null);
    }

    private static AchievementPool mint(int firstYear, int currentYear) throws Exception {
        FakeCalendar calendar = new FakeCalendar().event("yourmod_festival", firstYear, currentYear);
        AchievementAsset keepsake = AchievementAssetCodecTest.decodeRoot(KEEPSAKE, "lantern_keepsake");
        return AchievementAssetStore.resolve(Map.of("lantern_keepsake", keepsake), List.of(), calendar.reader())
                .pool();
    }

    @Test
    void aYearlyCopyIsNamedByItsBaseIdsConventionKeys() throws Exception {
        LangCatalog.overrideForTests(Map.of(
                "testpack.progression.achievement.lantern_keepsake.title", "Lantern Keepsake {0}",
                "testpack.progression.achievement.lantern_keepsake.desc", "Kept a lantern lit."));
        ContentText text = mint(2026, 2026).definition("lantern_keepsake_2026").achievement().text();

        assertEquals("achievement.lantern_keepsake.title", text.titleConventionKey(),
                "every year's copy shares its base's line");
        FormattedMessage title = text.title().getFormattedMessage();
        assertEquals("testpack.progression.achievement.lantern_keepsake.title", title.messageId);
        assertTrue(title.messageParams != null && title.messageParams.containsKey("0"), "the copy's year still binds");
        Message flavor = text.flavor();
        assertNotNull(flavor);
        assertEquals("testpack.progression.achievement.lantern_keepsake.desc", flavor.getFormattedMessage().messageId);
    }

    @Test
    void anExplicitKeyThatShipsStillOutranksTheConvention() throws Exception {
        LangCatalog.overrideForTests(Map.of(
                "testpack.progression.achievement.prospector.title", "By convention",
                "testpack.progression.yourmod.ach.prospector.title", "By the file"));
        AchievementAsset asset = AchievementAssetCodecTest.decodeRoot("""
                { "Text": { "TitleKey": "yourmod.ach.prospector.title" },
                  "Criteria": { "one": { "Kind": "BREAK_BLOCK", "Amount": 1 } } }
                """, "prospector");

        assertEquals("testpack.progression.yourmod.ach.prospector.title",
                asset.toDefinition().achievement().text().title().getFormattedMessage().messageId);
    }

    @Test
    void aNameThatResolvesNowhereIsReportedOncePerBase() throws Exception {
        LangCatalog.overrideForTests(Map.of("testpack.progression.unrelated", "x"));
        AchievementPool pool = mint(2025, 2026);

        List<Finding> unnamed = AchievementPoolValidator.validate(pool, null, null, null, null).stream()
                .filter(f -> ConventionKeys.UNRESOLVED_TITLE.equals(f.code())).toList();

        assertEquals(1, unnamed.size(), "three copies, one base, one line to write");
        assertEquals("lantern_keepsake", unnamed.get(0).sourceId());
    }
}
