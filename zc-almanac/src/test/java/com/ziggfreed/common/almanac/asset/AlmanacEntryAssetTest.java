package com.ziggfreed.common.almanac.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ziggfreed.common.almanac.AlmanacFixtures;
import com.ziggfreed.common.almanac.AlmanacSwitch;
import com.ziggfreed.common.almanac.page.AlmanacDestinations;
import com.ziggfreed.common.progress.MatchMode;
import com.ziggfreed.common.ui.route.Destinations;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * A season page's decode contract and what native {@code Parent} does to it; the {@code Hero} group (a
 * shipped image, a hero composed from item pictures, and the switch between them), the season's
 * {@code Accent} and the {@code Links} at the foot of its page; and what the server owner's file may
 * override over a pack's season.
 */
class AlmanacEntryAssetTest {

    /** Every hero and look leaf, and two links that open the Almanac. */
    private static final String DRESSED_PAGE = """
            { "Icon": "Test_Icon",
              "Accent": "#E8752A",
              "Hero": {
                "Art": "UI/Custom/Almanac/Test_Season.png",
                "Composition": {
                  "Background": "#1A0F08",
                  "BackgroundTexture": "UI/Custom/Almanac/Test_Fade.png",
                  "Items": [ { "Item": "Test_Lantern", "X": 620, "Y": 40, "Size": 96 },
                             { "Item": "Test_Pumpkin" },
                             { "Item": " ", "X": 10 } ] } },
              "Links": [
                { "TextKey": "almanac.test.link.book", "Destination": { "Type": "Almanac", "Event": "Other_Season" } },
                { "TextKey": "almanac.test.link.bare", "Destination": "Almanac" } ] }
            """;

    @TempDir
    Path dir;

    @AfterEach
    void clear() {
        Destinations.clearForTests();
        AlmanacOwnerLayers.setDirectory(AlmanacOwnerLayers.DEFAULT_DIRECTORY);
        AlmanacSwitch.resetForTests();
        AlmanacEntryConfig.getInstance().mergeOwnerLayer(Map.of());
        AlmanacEntryConfig.getInstance().mergePackLayer(Map.of());
    }

    @Test
    void aPageDecodesEveryLeafAndIsKeyedByItsLowerCasedFileName() throws Exception {
        AlmanacEntryAsset page = AlmanacFixtures.page(AlmanacFixtures.SEASON_PAGE, "Test_Season");

        assertEquals("test_season", page.getId());
        assertEquals("almanac.test.title", page.titleKey());
        assertEquals("almanac.test.flavor", page.flavorKey());
        assertEquals("Test_Icon", page.getIcon());
        assertEquals(10, page.orderOrLast());
        assertEquals("Test_Keepsake", page.getKeepsake());

        AlmanacStatAsset bombs = page.getStats().get("Bombs_Thrown");
        assertNotNull(bombs);
        assertEquals("USE_ITEM", bombs.getKind());
        assertEquals(MatchMode.PREFIX, bombs.effectiveMatchMode());
        assertEquals("Throw", bombs.getQualifier());
        assertEquals("almanac.test.bombs", bombs.getTextKey());
        assertEquals("Test_Bomb", bombs.getIcon());
        assertEquals(10, bombs.orderOrLast());
        assertFalse(bombs.isLiveOnly());
        assertTrue(page.getStats().get("Ghouls").isLiveOnly(), "an unauthored LiveOnly counts only while the season is on");
    }

    @Test
    void anEmptyPageReadsAsNothingAuthored() throws Exception {
        AlmanacEntryAsset page = AlmanacFixtures.page("{}", "Bare_Season");

        assertNull(page.titleKey());
        assertNull(page.getIcon());
        assertNull(page.getKeepsake());
        assertEquals(Integer.MAX_VALUE, page.orderOrLast(), "an unordered season sorts after every ordered one");
        assertTrue(page.getStats().isEmpty());
        assertNull(page.hero(), "no Hero group: the page composes its top from the season's own picture");
        assertNull(page.accent());
        assertTrue(page.links().isEmpty());
        assertTrue(page.findings().isEmpty());
    }

    @Test
    void aChildRetunesOneLineByItsKeyAndKeepsTheRest() throws Exception {
        AlmanacEntryAsset parent = AlmanacFixtures.page(AlmanacFixtures.SEASON_PAGE, "Test_Season");
        AlmanacEntryAsset child = AlmanacFixtures.page("{ \"Stats\": { \"Ghouls\": { \"Order\": 5 } } }",
                "Test_Season_Retuned", "test_season", parent);

        assertEquals(5, child.getStats().get("Ghouls").orderOrLast());
        assertEquals("KILL_ENTITY", child.getStats().get("Ghouls").getKind(), "the retuned line keeps its other leaves");
        assertNotNull(child.getStats().get("Bombs_Thrown"), "a line the child did not mention stays");
        assertEquals("Test_Keepsake", child.getKeepsake(), "a top-level leaf the child did not mention stays");
    }

    @Test
    void theHeroGroupAndTheAccentDecode() throws Exception {
        AlmanacDestinations.register();
        AlmanacEntryAsset page = AlmanacFixtures.page(DRESSED_PAGE, "Test_Season");

        AlmanacHeroAsset hero = page.hero();
        assertNotNull(hero);
        assertEquals("UI/Custom/Almanac/Test_Season.png", hero.art());
        assertTrue(hero.showArt(), "an unauthored ShowArt shows the art");
        AlmanacHeroAsset.Composition composition = hero.composition();
        assertNotNull(composition);
        assertEquals("#1a0f08", composition.background(), "a colour reads in one spelling");
        assertEquals("UI/Custom/Almanac/Test_Fade.png", composition.backgroundTexture());
        assertEquals(List.of("Test_Lantern", "Test_Pumpkin"),
                composition.items().stream().map(AlmanacHeroAsset.Placement::item).toList(),
                "a placement with no item is no placement");
        AlmanacHeroAsset.Placement lantern = composition.items().get(0);
        assertEquals(620, lantern.x());
        assertEquals(40, lantern.y());
        assertEquals(96, lantern.size());
        AlmanacHeroAsset.Placement pumpkin = composition.items().get(1);
        assertNull(pumpkin.x(), "an unauthored place is left to the reader's default");
        assertNull(pumpkin.size());
        assertEquals("#e8752a", page.accent());
        assertTrue(page.findings().isEmpty(), page.findings().toString());
    }

    @Test
    void showArtFalseIsReadAsAuthoredAndKeepsTheArtNamed() throws Exception {
        AlmanacEntryAsset page = AlmanacFixtures.page(
                "{ \"Hero\": { \"Art\": \"UI/Custom/A.png\", \"ShowArt\": false } }", "Test_Season");

        assertNotNull(page.hero());
        assertEquals("UI/Custom/A.png", page.hero().art(), "the view decides not to draw it");
        assertFalse(page.hero().showArt());
        assertNull(page.hero().composition());
    }

    @Test
    void aColourThatIsNotSixDigitHexReadsAsNoneAndIsReported() throws Exception {
        AlmanacEntryAsset page = AlmanacFixtures.page(
                "{ \"Accent\": \"orange\", \"Hero\": { \"Composition\": { \"Background\": \"#12345\" } } }",
                "Test_Season");

        assertNull(page.accent());
        assertNotNull(page.hero().composition());
        assertNull(page.hero().composition().background());
        assertEquals(2, page.findings().size());
        for (Finding finding : page.findings()) {
            assertEquals(Severity.WARNING, finding.severity(), "a content slip is a warning, never an error");
            assertEquals(AlmanacEntryAsset.FINDING_COLOUR, finding.code());
            assertEquals("test_season", finding.sourceId());
        }
    }

    @Test
    void moreItemsThanTheHeroDrawsIsReported() throws Exception {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < 13; i++) {
            items.append(i == 0 ? "" : ", ").append("{ \"Item\": \"Test_Item_").append(i).append("\" }");
        }
        AlmanacEntryAsset page = AlmanacFixtures.page(
                "{ \"Hero\": { \"Composition\": { \"Items\": [ " + items + " ] } } }", "Test_Season");

        assertEquals(13, page.hero().composition().items().size(), "the file is read as written; the view caps it");
        assertEquals(List.of(AlmanacEntryAsset.FINDING_HERO_ITEMS),
                page.findings().stream().map(Finding::code).toList());
    }

    @Test
    void aMalformedCompositionFallsBackToTheOneItWouldHaveInheritedAndThePageStillLoads() throws Exception {
        AlmanacDestinations.register();
        AlmanacEntryAsset parent = AlmanacFixtures.page(DRESSED_PAGE, "Test_Season");
        AlmanacEntryAsset child = AlmanacFixtures.page("""
                { "Order": 7, "Hero": { "Art": "UI/Custom/B.png", "Composition": { "Items": "a lantern" } } }
                """, "Test_Season_Broken", "test_season", parent);

        assertEquals(7, child.orderOrLast(), "the page decoded whole");
        assertEquals("UI/Custom/B.png", child.hero().art(), "the child's well-formed leaves apply");
        assertEquals(2, child.hero().composition().items().size(), "the broken composition is the parent's");

        AlmanacEntryAsset alone = AlmanacFixtures.page(
                "{ \"Order\": 3, \"Hero\": { \"Composition\": { \"Items\": [ { \"X\": \"far\" } ] } } }", "Test_Season");
        assertEquals(3, alone.orderOrLast());
        assertNull(alone.hero().composition(), "with nothing to fall back to, there is no composition");
    }

    @Test
    void linksDecodeWithTheirDestinations() throws Exception {
        AlmanacDestinations.register();
        AlmanacEntryAsset page = AlmanacFixtures.page(DRESSED_PAGE, "Test_Season");

        assertEquals(2, page.links().size());
        AlmanacLinkAsset first = page.links().get(0);
        assertEquals("almanac.test.link.book", first.textKey());
        AlmanacDestinations.Almanac opens = (AlmanacDestinations.Almanac) first.destination();
        assertNotNull(opens);
        assertEquals("Other_Season", opens.getEvent());
        assertNull(((AlmanacDestinations.Almanac) page.links().get(1).destination()).getEvent(),
                "the bare-string form is the same destination with no fields");
    }

    @Test
    void aLinkToADestinationNothingRegisteredIsDroppedWithAFindingAndThePageStillLoads() throws Exception {
        AlmanacDestinations.register();
        AlmanacEntryAsset page = AlmanacFixtures.page("""
                { "Order": 10,
                  "Links": [
                    { "TextKey": "almanac.test.link.shop", "Destination": { "Type": "Nope_Shop", "Shop": "x" } },
                    { "TextKey": "almanac.test.link.ok", "Destination": "Almanac" },
                    { "Destination": "Almanac" } ] }
                """, "Test_Season");

        assertEquals(10, page.orderOrLast(), "the page decoded whole");
        assertEquals(List.of("almanac.test.link.ok"), page.links().stream().map(AlmanacLinkAsset::textKey).toList(),
                "a link to a screen no installed mod opens, and a link with no words, are left out");
        assertEquals(2, page.findings().size());
        for (Finding finding : page.findings()) {
            assertEquals(Severity.WARNING, finding.severity());
            assertEquals(AlmanacEntryAsset.FINDING_LINK, finding.code());
        }
    }

    @Test
    void aChildInheritsTheHeroLeafByLeafAndTheLinksWhole() throws Exception {
        AlmanacDestinations.register();
        AlmanacEntryAsset parent = AlmanacFixtures.page(DRESSED_PAGE, "Test_Season");
        AlmanacEntryAsset child = AlmanacFixtures.page("{ \"Hero\": { \"ShowArt\": false } }", "Test_Season_Plain",
                "test_season", parent);

        assertFalse(child.hero().showArt());
        assertEquals("UI/Custom/Almanac/Test_Season.png", child.hero().art(), "a hero leaf the child left out stays");
        assertEquals(2, child.hero().composition().items().size());
        assertEquals("#e8752a", child.accent());
        assertEquals(2, child.links().size(), "an inherited list comes whole");
    }

    @Test
    void aChildCompositionReplacesTheParentsWhole() throws Exception {
        AlmanacDestinations.register();
        AlmanacEntryAsset parent = AlmanacFixtures.page(DRESSED_PAGE, "Test_Season");
        AlmanacEntryAsset child = AlmanacFixtures.page(
                "{ \"Hero\": { \"Composition\": { \"Items\": [ { \"Item\": \"Test_Bomb\" } ] } } }",
                "Test_Season_Own", "test_season", parent);

        AlmanacHeroAsset.Composition composition = child.hero().composition();
        assertEquals(List.of("Test_Bomb"), composition.items().stream().map(AlmanacHeroAsset.Placement::item).toList(),
                "a list replaces, never appends");
        assertNull(composition.background(), "a composition is one piece: the parent's background does not leak in");
        assertNull(composition.backgroundTexture());
        assertEquals("UI/Custom/Almanac/Test_Season.png", child.hero().art());
    }

    @Test
    void anOwnersArtReplacesThePacks() throws Exception {
        AlmanacEntryAsset resolved = ownerOver("{ \"Test_Season\": { \"Hero\": { \"Art\": \"UI/Custom/Owner/Mine.png\" } } }");

        assertEquals("UI/Custom/Owner/Mine.png", resolved.hero().art());
        assertTrue(resolved.hero().showArt());
        assertEquals(2, resolved.hero().composition().items().size(), "the pack's composition stays");
    }

    @Test
    void anOwnersCompositionReplacesThePacksWhole() throws Exception {
        AlmanacEntryAsset resolved = ownerOver("""
                { "Test_Season": { "Hero": { "Composition": {
                    "Background": "#202020", "Items": [ { "Item": "Test_Bomb", "X": 30, "Y": 30, "Size": 48 } ] } } } }
                """);

        AlmanacHeroAsset.Composition composition = resolved.hero().composition();
        assertEquals("#202020", composition.background());
        assertNull(composition.backgroundTexture(), "the pack's texture does not leak into the owner's layout");
        assertEquals(List.of("Test_Bomb"), composition.items().stream().map(AlmanacHeroAsset.Placement::item).toList());
        assertEquals("UI/Custom/Almanac/Test_Season.png", resolved.hero().art(), "the pack's art stays named");
    }

    @Test
    void anOwnersShowArtFalseBeatsThePacksArt() throws Exception {
        AlmanacEntryAsset resolved = ownerOver("{ \"Test_Season\": { \"Hero\": { \"ShowArt\": false }, \"Accent\": \"#3A8FE8\" } }");

        assertFalse(resolved.hero().showArt());
        assertEquals("UI/Custom/Almanac/Test_Season.png", resolved.hero().art());
        assertEquals("#3a8fe8", resolved.accent());
    }

    @Test
    void theSkyAndTheGlowDecodeAndAMalformedColourIsIgnoredAndReported() throws Exception {
        AlmanacEntryAsset page = AlmanacFixtures.page("""
                { "Hero": { "Composition": {
                    "Gradient": { "Top": "#1A2A4A", "Bottom": "#0A1119" },
                    "Glow": { "Color": "#A0501A", "X": 514, "Y": -78, "Size": 400 },
                    "Items": [ { "Item": "Test_Lantern" } ] } } }
                """, "Test_Season");

        AlmanacHeroAsset.Composition composition = page.hero().composition();
        assertEquals("#1a2a4a", composition.gradient().top());
        assertEquals("#0a1119", composition.gradient().bottom());
        assertEquals("#a0501a", composition.glow().color());
        assertEquals(514, composition.glow().x());
        assertEquals(-78, composition.glow().y(), "a glow's box may start above the plate");
        assertEquals(400, composition.glow().size());
        assertTrue(page.findings().isEmpty(), page.findings().toString());

        AlmanacEntryAsset broken = AlmanacFixtures.page("""
                { "Hero": { "Composition": { "Gradient": { "Top": "#1A2A4A" }, "Glow": { "Color": "amber" } } } }
                """, "Test_Season");
        assertNull(broken.hero().composition().gradient(), "a sky needs both colours");
        assertNull(broken.hero().composition().glow(), "a glow needs a colour");
        assertEquals(List.of(AlmanacEntryAsset.FINDING_COLOUR, AlmanacEntryAsset.FINDING_COLOUR),
                broken.findings().stream().map(Finding::code).toList());
    }

    @Test
    void anOwnersCompositionWithASkyAndAGlowReplacesThePacks() throws Exception {
        AlmanacEntryAsset resolved = ownerOver("""
                { "Test_Season": { "Hero": { "Composition": {
                    "Gradient": { "Top": "#000000", "Bottom": "#202020" },
                    "Glow": { "Color": "#FFFFFF", "Size": 100 },
                    "Items": [ { "Item": "Test_Bomb" } ] } } } }
                """);

        AlmanacHeroAsset.Composition composition = resolved.hero().composition();
        assertEquals("#000000", composition.gradient().top());
        assertEquals("#ffffff", composition.glow().color());
        assertEquals(100, composition.glow().size());
        assertEquals("#1a0f08", AlmanacFixtures.page(DRESSED_PAGE, "Test_Season").hero().composition().background(),
                "the pack's own page is untouched");
        assertNull(composition.background(), "the owner's composition carries no leaf of the pack's");
    }

    @Test
    void anOwnerWhoSetsNothingLeavesThePacksSeason() throws Exception {
        AlmanacEntryAsset resolved = ownerOver("{ \"$Enabled\": true }");

        assertTrue(resolved.hero().showArt());
        assertEquals("UI/Custom/Almanac/Test_Season.png", resolved.hero().art());
        assertEquals("#1a0f08", resolved.hero().composition().background());
        assertEquals("#e8752a", resolved.accent());
    }

    @Test
    void aMalformedOwnerCompositionFallsBackToThePacksAndKeepsTheOwnersOtherLeaves() throws Exception {
        AlmanacEntryAsset resolved = ownerOver("""
                { "Test_Season": { "Order": 2, "Hero": { "ShowArt": false, "Composition": { "Items": 12 } } } }
                """);

        assertEquals(2, resolved.orderOrLast(), "the season is not dropped, and the owner's other leaves apply");
        assertFalse(resolved.hero().showArt());
        assertEquals(2, resolved.hero().composition().items().size(), "the pack's composition stands");
        assertEquals("#1a0f08", resolved.hero().composition().background());
    }

    /** The fixture pack season, with {@code ownerFile} written as the server owner's almanac.json. */
    private AlmanacEntryAsset ownerOver(String ownerFile) throws Exception {
        AlmanacDestinations.register();
        AlmanacEntryConfig.getInstance().mergePackLayer(Map.of("test_season",
                AlmanacFixtures.page(DRESSED_PAGE, "Test_Season")));
        AlmanacOwnerLayers.setDirectory(dir);
        write(ownerFile);
        AlmanacOwnerLayers.reload();
        AlmanacEntryAsset resolved = AlmanacEntryConfig.getInstance().resolve("test_season");
        assertNotNull(resolved);
        assertNotNull(resolved.hero());
        return resolved;
    }

    private void write(String body) throws IOException {
        Files.writeString(dir.resolve(AlmanacOwnerLayers.FILE), body, StandardCharsets.UTF_8);
    }
}
