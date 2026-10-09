package com.ziggfreed.common.almanac.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.achievement.asset.AchievementAsset;
import com.ziggfreed.common.achievement.asset.AchievementAssetStore;
import com.ziggfreed.common.almanac.AlmanacFixtures;
import com.ziggfreed.common.almanac.AlmanacSwitch;
import com.ziggfreed.common.occurrence.Occurrence;
import com.ziggfreed.common.occurrence.OccurrenceSource;
import com.ziggfreed.common.occurrence.Occurrences;
import com.ziggfreed.common.progress.ObjectiveKindRegistry;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;
import com.ziggfreed.common.validation.TextKeyAudit;

/**
 * One case per code, each driven through the pure core with fakes for the calendar, the item store,
 * the objective vocabulary and the lang catalogue; the page's own findings folded in; every finding
 * filed under the {@code almanac} domain; and the engine walk's two rules of its own: an Almanac the
 * owner switched off reports nothing, and the calendar is asked through the occurrence slot.
 */
class AlmanacValidatorTest {

    private static final Predicate<String> ALL_EVENTS = id -> true;
    private static final Predicate<String> ALL_ITEMS = id -> true;
    private static final Predicate<String> ALL_KEYS = key -> true;

    @AfterEach
    void bareAlmanac() {
        AlmanacSwitch.resetForTests();
        Occurrences.resetForTests();
        AlmanacEntryConfig.getInstance().mergeOwnerLayer(Map.of());
        AlmanacEntryConfig.getInstance().mergePackLayer(Map.of());
        AchievementAssetStore.getInstance().merge(Map.of());
    }

    @Nonnull
    private static AchievementAsset achievement(@Nonnull String json, @Nonnull String id) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(AchievementAsset.class, id, null);
        return AchievementAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), null, new AssetExtraInfo<>(data));
    }

    @Nonnull
    private static List<Finding> audit(@Nonnull List<AlmanacEntryAsset> pages, @Nonnull Predicate<String> eventLoaded,
            @Nonnull Predicate<String> itemKnown, @Nullable ObjectiveKindRegistry kinds,
            @Nonnull Predicate<String> keyShipped) {
        return AlmanacValidator.audit(pages, eventLoaded, itemKnown, kinds, keyShipped);
    }

    @Nonnull
    private static List<Finding> audit(@Nonnull AlmanacEntryAsset... pages) {
        return audit(List.of(pages), ALL_EVENTS, ALL_ITEMS, new ObjectiveKindRegistry(), ALL_KEYS);
    }

    @Nonnull
    private static List<String> codes(@Nonnull List<Finding> findings) {
        List<String> out = new ArrayList<>();
        for (Finding finding : findings) {
            out.add(finding.code());
        }
        return out;
    }

    @Nonnull
    private static Finding only(@Nonnull List<Finding> findings, @Nonnull String code) {
        List<Finding> matching = findings.stream().filter(f -> f.code().equals(code)).toList();
        assertEquals(1, matching.size(), () -> "expected one " + code + " finding, got " + findings);
        return matching.get(0);
    }

    @Test
    void aSoundPageReportsNothing() throws IOException {
        assertTrue(audit(AlmanacFixtures.page(AlmanacFixtures.SEASON_PAGE, "Test_Season")).isEmpty());
    }

    @Test
    void thePagesOwnFindingsAreFoldedIn() throws IOException {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i <= AlmanacEntryAsset.HERO_MAX_ITEMS; i++) {
            items.append(i == 0 ? "" : ", ").append("{ \"Item\": \"Test_Lantern\" }");
        }
        AlmanacEntryAsset page = AlmanacFixtures.page("{ \"Accent\": \"orange\", \"Links\": [ { \"TextKey\": \"x\" } ],"
                + " \"Hero\": { \"Composition\": { \"Items\": [ " + items + " ] } } }", "Test_Season");

        List<Finding> findings = audit(page);

        assertEquals(Severity.WARNING, only(findings, AlmanacEntryAsset.FINDING_COLOUR).severity());
        assertEquals(Severity.WARNING, only(findings, AlmanacEntryAsset.FINDING_LINK).severity());
        assertEquals(Severity.WARNING, only(findings, AlmanacEntryAsset.FINDING_HERO_ITEMS).severity());
    }

    @Test
    void aPageForAnEventTheCalendarDoesNotLoadIsAWarning() throws IOException {
        AlmanacEntryAsset page = AlmanacFixtures.page("{ }", "Sping_Fair");

        Finding finding = only(audit(List.of(page), "spring_fair"::equals, ALL_ITEMS, null, ALL_KEYS),
                AlmanacValidator.UNKNOWN_EVENT);

        assertEquals(Severity.WARNING, finding.severity());
        assertEquals("sping_fair", finding.sourceId());
        assertTrue(audit(List.of(AlmanacFixtures.page("{ }", "Spring_Fair")), "spring_fair"::equals, ALL_ITEMS, null,
                ALL_KEYS).isEmpty());
    }

    @Test
    void aPageWhoseNameTheTallyFormatReservesIsAnErrorAndNothingMoreIsAsked() throws IOException {
        AlmanacEntryAsset page = AlmanacFixtures.page("{ \"Icon\": \"Nope\" }", "Spring@Fair");

        List<Finding> findings = audit(List.of(page), id -> false, id -> false, null, key -> false);

        assertEquals(List.of(AlmanacValidator.PAGE_ID_UNUSABLE), codes(findings));
        assertEquals(Severity.ERROR, findings.get(0).severity());
    }

    @Test
    void picturesNamingNoLoadedItemAreWarnings() throws IOException {
        AlmanacEntryAsset page = AlmanacFixtures.page("""
                { "Icon": "Test_Icon",
                  "Hero": { "Composition": { "Items": [ { "Item": "Test_Lantern" }, { "Item": "Test_Lantren" } ] } },
                  "Stats": { "Kites": { "Kind": "USE_ITEM", "Target": "Kite_", "Icon": "Test_Kit" } } }
                """, "Test_Season");

        List<Finding> findings = audit(List.of(page), ALL_EVENTS, id -> id.equals("Test_Lantern"),
                new ObjectiveKindRegistry(), ALL_KEYS);

        assertEquals(List.of(AlmanacValidator.UNKNOWN_ICON, AlmanacValidator.UNKNOWN_HERO_ITEM,
                AlmanacValidator.UNKNOWN_ICON), codes(findings));
        assertTrue(findings.get(1).message().contains("'Test_Lantren'"), findings.get(1).message());
        assertTrue(findings.get(2).message().contains("Kites"), findings.get(2).message());
        for (Finding finding : findings) {
            assertEquals(Severity.WARNING, finding.severity());
        }
    }

    @Test
    void aTallyLineThatCanNeverCountSaysWhy() throws IOException {
        AlmanacEntryAsset page = AlmanacFixtures.page("""
                { "Stats": {
                    "Bad:Name": { "Kind": "USE_ITEM" },
                    "No_Kind": { "Target": "Kite_" },
                    "Typo": { "Kind": "USE_ITME" },
                    "Silent": { "Kind": "NEVER_FIRED" } } }
                """, "Test_Season");
        ObjectiveKindRegistry kinds = new ObjectiveKindRegistry();
        kinds.register("NEVER_FIRED", null, false, false);

        List<Finding> findings = audit(List.of(page), ALL_EVENTS, ALL_ITEMS, kinds, ALL_KEYS);

        assertEquals(Severity.ERROR, only(findings, AlmanacValidator.STAT_ID_UNUSABLE).severity());
        assertEquals(Severity.ERROR, only(findings, AlmanacValidator.MISSING_KIND).severity());
        Finding unknown = only(findings, AlmanacValidator.UNKNOWN_KIND);
        assertEquals(Severity.WARNING, unknown.severity());
        assertTrue(unknown.message().contains("'USE_ITME'"), unknown.message());
        assertEquals(Severity.ERROR, only(findings, AlmanacValidator.UNPRODUCIBLE_KIND).severity());
        assertEquals(4, findings.size(), () -> "one finding per broken line: " + findings);
    }

    @Test
    void withNoVocabularyInHandTheKindsAreNotAsked() throws IOException {
        AlmanacEntryAsset page = AlmanacFixtures.page("{ \"Stats\": { \"Typo\": { \"Kind\": \"USE_ITME\" } } }",
                "Test_Season");

        assertTrue(audit(List.of(page), ALL_EVENTS, ALL_ITEMS, null, ALL_KEYS).isEmpty());
    }

    @Test
    void everyKeyThePageShowsThatNoLangFileShipsIsAWarning() throws IOException {
        List<Finding> findings = audit(List.of(AlmanacFixtures.page(AlmanacFixtures.SEASON_PAGE, "Test_Season")),
                ALL_EVENTS, ALL_ITEMS, new ObjectiveKindRegistry(), key -> key.equals("almanac.test.title"));

        assertEquals(List.of(TextKeyAudit.UNKNOWN_TEXT_KEY, TextKeyAudit.UNKNOWN_TEXT_KEY), codes(findings),
                "the FlavorKey and the Bombs_Thrown line's TextKey");
        assertTrue(findings.get(1).message().contains("Bombs_Thrown"), findings.get(1).message());
    }

    @Test
    void everyFindingIsFiledUnderTheAlmanacDomain() throws IOException {
        AlmanacEntryAsset page = AlmanacFixtures.page("{ \"Accent\": \"orange\", \"Icon\": \"Nope\","
                + " \"Text\": { \"TitleKey\": \"nope\" }, \"Stats\": { \"Empty\": { } } }", "Test_Season");

        List<Finding> findings = audit(List.of(page), id -> false, id -> false, new ObjectiveKindRegistry(),
                key -> false);

        assertTrue(findings.size() >= 5, "plenty is wrong here: " + findings);
        for (Finding finding : findings) {
            assertEquals(AlmanacValidator.DOMAIN, finding.domain());
            assertEquals("test_season", finding.sourceId());
        }
    }

    @Test
    void theEngineWalkAsksTheOccurrenceSlotAndReportsNothingWhileTheAlmanacIsOff() throws IOException {
        AlmanacEntryConfig.getInstance().mergePackLayer(Map.of(
                "spring_fair", AlmanacFixtures.page("{ }", "Spring_Fair"),
                "summer_fair", AlmanacFixtures.page("{ }", "Summer_Fair")));
        Occurrences.fill(onlyKnows("spring_fair"));

        List<Finding> findings = AlmanacValidator.audit();
        assertEquals(List.of(AlmanacValidator.UNKNOWN_EVENT), codes(findings));
        assertEquals("summer_fair", findings.get(0).sourceId());

        AlmanacSwitch.set(false);
        assertTrue(AlmanacValidator.audit().isEmpty(), "off means absent: there is nothing to report");
    }

    // A page's Keepsake is asked of the loaded achievement files: one naming no achievement, or only an Abstract base
    // that never folds, leaves the page with no keepsake shelf. A file named as one year's copy of it
    // (<Keepsake>_<yyyy>) is one the page finds, as the Almanac's own reading does.
    @Test
    void aKeepsakeNamingNoLoadedAchievementIsAWarning() throws IOException {
        AlmanacEntryConfig.getInstance().mergePackLayer(Map.of(
                "hallows_eve", AlmanacFixtures.page("{ \"Keepsake\": \"Lantern\" }", "Hallows_Eve"),
                "harvest_feast", AlmanacFixtures.page("{ \"Keepsake\": \"Feast_Keepsake\" }", "Harvest_Feast"),
                "spring_fair", AlmanacFixtures.page("{ \"Keepsake\": \"Blossom\" }", "Spring_Fair"),
                "winter_fair", AlmanacFixtures.page("{ \"Keepsake\": \"Snowflake\" }", "Winter_Fair")));
        AchievementAssetStore.getInstance().merge(Map.of(
                "lantern", achievement("{ \"Occurrence\": { \"Event\": \"hallows_eve\" } }", "lantern"),
                "blossom_2026", achievement("{ }", "blossom_2026"),
                "snowflake", achievement("{ \"Abstract\": true }", "snowflake")));

        List<Finding> findings = AlmanacValidator.audit();

        assertEquals(List.of(AlmanacValidator.UNKNOWN_KEEPSAKE, AlmanacValidator.UNKNOWN_KEEPSAKE), codes(findings),
                findings.toString());
        assertEquals(List.of("harvest_feast", "winter_fair"), findings.stream().map(Finding::sourceId).toList(),
                "a keepsake no file names, and one only an Abstract base names");
        assertEquals(Severity.WARNING, findings.get(0).severity(), "another pack may ship it");
    }

    @Test
    void thePureCoreAsksTheKeepsakeOnlyWithTheAchievementsInHand() throws IOException {
        AlmanacEntryAsset page = AlmanacFixtures.page("{ \"Keepsake\": \"Lantern\" }", "Hallows_Eve");

        Finding finding = only(AlmanacValidator.audit(List.of(page), ALL_EVENTS, ALL_ITEMS, null, ALL_KEYS,
                "candle"::equals), AlmanacValidator.UNKNOWN_KEEPSAKE);
        assertEquals(Severity.WARNING, finding.severity());
        assertEquals("hallows_eve", finding.sourceId());
        assertTrue(finding.message().contains("Lantern"), finding.message());

        assertTrue(AlmanacValidator.audit(List.of(page), ALL_EVENTS, ALL_ITEMS, null, ALL_KEYS, "lantern"::equals)
                .isEmpty(), "the keepsake is asked as every key spells it, trimmed and lower-cased");
        assertTrue(audit(page).isEmpty(), "with no achievements in hand, the keepsake is not asked");
    }

    @Test
    void anUnfilledOccurrenceSlotCannotTellAndReportsNoEventAsUnknown() throws IOException {
        AlmanacEntryConfig.getInstance().mergePackLayer(Map.of("summer_fair", AlmanacFixtures.page("{ }", "Summer_Fair")));

        assertTrue(AlmanacValidator.audit().isEmpty());
    }

    /** A calendar that has loaded exactly {@code eventId}, with no runs. */
    @Nonnull
    private static OccurrenceSource onlyKnows(@Nonnull String eventId) {
        return new OccurrenceSource() {
            @Override
            public boolean isEnabled(@Nonnull String id) {
                return false;
            }

            @Override
            @Nullable
            public Occurrence live(@Nonnull String id, long nowMs) {
                return null;
            }

            @Override
            @Nonnull
            public List<Occurrence> history(@Nonnull String id, long nowMs) {
                return List.of();
            }

            @Override
            @Nullable
            public Integer currentYear(@Nonnull String id, long nowMs) {
                return eventId.equals(id) ? 2026 : null;
            }
        };
    }
}
