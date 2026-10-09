package com.ziggfreed.common.almanac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.achievement.asset.AchievementAsset;
import com.ziggfreed.common.achievement.asset.AchievementAssetStore;
import com.ziggfreed.common.almanac.asset.AlmanacEntryAsset;
import com.ziggfreed.common.almanac.asset.AlmanacEntryConfig;
import com.ziggfreed.common.almanac.asset.AlmanacValidator;
import com.ziggfreed.common.occurrence.OccurrenceSource;
import com.ziggfreed.common.occurrence.Occurrences;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * A season's Almanac page names its keepsake, and the cross-season ladder picks keepsakes by tag: the
 * check warns when the two disagree, either way, and says nothing when they agree or when there is no
 * ladder to agree with.
 */
class AlmanacKeepsakeCheckTest {

    private static final String LADDER = """
            { "Listing": { "Category": "Seasons" },
              "MetaSelector": { "Category": "Seasons", "Tags": [ "season_keepsake" ], "AnyYear": true, "Needs": 2 } }
            """;

    @AfterEach
    void bareStores() {
        AlmanacSwitch.resetForTests();
        Occurrences.resetForTests();
        AlmanacEntryConfig.getInstance().mergeOwnerLayer(Map.of());
        AlmanacEntryConfig.getInstance().mergePackLayer(Map.of());
        AchievementAssetStore.getInstance().merge(Map.of());
    }

    private static AchievementAsset achievement(@Nonnull String json, @Nonnull String id) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(AchievementAsset.class, id, null);
        return AchievementAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), null, new AssetExtraInfo<>(data));
    }

    private static AchievementAsset yearly(@Nonnull String id, @Nonnull String event, boolean tagged)
            throws IOException {
        return achievement("""
                { "Occurrence": { "Event": "@EVENT" },
                  "Listing": { "Category": "Seasons", "Subcategory": "@EVENT"@TAGS },
                  "Criteria": { "one": { "Kind": "BREAK_BLOCK", "Amount": 1 } } }
                """.replace("@EVENT", event)
                .replace("@TAGS", tagged ? ", \"Tags\": [ \"season_keepsake\" ]" : ""), id);
    }

    /** A yearly capstone over its own season's achievements, carrying the ladder's tag. */
    private static AchievementAsset yearlyCapstone(@Nonnull String id, @Nonnull String event) throws IOException {
        return achievement("""
                { "Occurrence": { "Event": "@EVENT" },
                  "Listing": { "Category": "Seasons", "Subcategory": "@EVENT", "Tags": [ "season_keepsake" ] },
                  "MetaSelector": { "Category": "Seasons", "Subcategory": "@EVENT" } }
                """.replace("@EVENT", event), id);
    }

    private static Map<String, AlmanacEntryAsset> twoSeasons() throws IOException {
        return Map.of(
                "hallows_eve", AlmanacFixtures.page("{ \"Keepsake\": \"Hallowed\" }", "Hallows_Eve"),
                "harvest_feast", AlmanacFixtures.page("{ \"Keepsake\": \"Feast_Keepsake\" }", "Harvest_Feast"));
    }

    @Test
    void aLadderAndTheSeasonsKeepsakesThatAgreeReportNothing() throws Exception {
        Map<String, AchievementAsset> achievements = Map.of(
                "two_seasons", achievement(LADDER, "two_seasons"),
                "hallowed", yearly("hallowed", "hallows_eve", true),
                "feast_keepsake", yearly("feast_keepsake", "harvest_feast", true));

        assertEquals(List.of(), AlmanacKeepsakeCheck.findings(twoSeasons(), achievements));
    }

    @Test
    void aKeepsakeTheLadderDoesNotPickIsReportedAgainstItsSeason() throws Exception {
        Map<String, AchievementAsset> achievements = Map.of(
                "two_seasons", achievement(LADDER, "two_seasons"),
                "hallowed", yearly("hallowed", "hallows_eve", true),
                "feast_keepsake", yearly("feast_keepsake", "harvest_feast", false));

        List<Finding> findings = AlmanacKeepsakeCheck.findings(twoSeasons(), achievements);

        assertEquals(1, findings.size(), findings.toString());
        Finding finding = findings.get(0);
        assertEquals(AlmanacKeepsakeCheck.KEEPSAKE_NOT_PICKED, finding.code());
        assertEquals(Severity.WARNING, finding.severity(), "a later pack may carry the tag");
        assertEquals("harvest_feast", finding.sourceId());
        assertTrue(finding.message().contains("two_seasons") && finding.message().contains("feast_keepsake"),
                finding.message());
    }

    @Test
    void aPickedYearlyAchievementNoPageNamesIsReported() throws Exception {
        Map<String, AchievementAsset> achievements = Map.of(
                "two_seasons", achievement(LADDER, "two_seasons"),
                "hallowed", yearly("hallowed", "hallows_eve", true),
                "feast_keepsake", yearly("feast_keepsake", "harvest_feast", true),
                "ghoul_breaker", yearly("ghoul_breaker", "hallows_eve", true));

        List<Finding> findings = AlmanacKeepsakeCheck.findings(twoSeasons(), achievements);

        assertEquals(1, findings.size(), findings.toString());
        assertEquals(AlmanacKeepsakeCheck.PICKED_NOT_A_KEEPSAKE, findings.get(0).code());
        assertEquals("ghoul_breaker", findings.get(0).sourceId(),
                "it counts toward the ladder without showing on any season's keepsake shelf");
    }

    @Test
    void withoutACrossSeasonLadderThereIsNothingToAgreeWith() throws Exception {
        Map<String, AchievementAsset> achievements = Map.of(
                "hallowed", yearly("hallowed", "hallows_eve", false),
                "feast_keepsake", yearly("feast_keepsake", "harvest_feast", false));

        assertEquals(List.of(), AlmanacKeepsakeCheck.findings(twoSeasons(), achievements));
    }

    /**
     * The fold never picks a capstone (one listing MetaChildren or writing a MetaSelector), so neither does
     * the check: a season whose keepsake is one never counts toward the ladder, tag or no tag, and a tagged
     * capstone no page names counts toward nothing.
     */
    @Test
    void aCapstoneIsNeverPickedSoACapstoneKeepsakeIsReportedAndATaggedCapstoneIsNot() throws Exception {
        Map<String, AchievementAsset> achievements = Map.of(
                "two_seasons", achievement(LADDER, "two_seasons"),
                "hallowed", yearly("hallowed", "hallows_eve", true),
                "feast_keepsake", yearlyCapstone("feast_keepsake", "harvest_feast"),
                "hallows_eve_champion", yearlyCapstone("hallows_eve_champion", "hallows_eve"));

        List<Finding> findings = AlmanacKeepsakeCheck.findings(twoSeasons(), achievements);

        assertEquals(1, findings.size(), findings.toString());
        assertEquals(AlmanacKeepsakeCheck.KEEPSAKE_NOT_PICKED, findings.get(0).code());
        assertEquals("harvest_feast", findings.get(0).sourceId());
    }

    /**
     * Review Focus 4: an owner who switches a season off in calendar.json leaves its page and its keepsake
     * loaded, and the check reads the loaded files, never the calendar, so the season's keepsake is still
     * its season's and no orphan is reported.
     */
    @Test
    void aSeasonSwitchedOffInTheCalendarIsCheckedLikeAnyOtherAndLeavesNoOrphan() throws Exception {
        AlmanacEntryConfig.getInstance().mergePackLayer(twoSeasons());
        AchievementAssetStore.getInstance().merge(Map.of(
                "two_seasons", achievement(LADDER, "two_seasons"),
                "hallowed", yearly("hallowed", "hallows_eve", true),
                "feast_keepsake", yearly("feast_keepsake", "harvest_feast", true)));
        Occurrences.fill(OccurrenceSource.NONE);

        assertEquals(List.of(), AlmanacKeepsakeCheck.findings(),
                "every season is switched off, and every keepsake still belongs to its season's page");
    }

    /**
     * One boot prints a finding once: a boot that asks for zc's boot audit has it counted in the audit's
     * Almanac pass, and the build hook stays quiet; any other boot has the hook print it. While the owner
     * has the Almanac switched off, off means absent: neither says anything.
     */
    @Test
    void theBuildHookAndTheBootAuditsAlmanacPassNeverBothPrintAFinding() throws Exception {
        AlmanacEntryConfig.getInstance().mergePackLayer(twoSeasons());
        AchievementAssetStore.getInstance().merge(Map.of(
                "two_seasons", achievement(LADDER, "two_seasons"),
                "hallowed", yearly("hallowed", "hallows_eve", true),
                "feast_keepsake", yearly("feast_keepsake", "harvest_feast", false)));
        List<String> printed = new ArrayList<>();

        AlmanacKeepsakeCheck.logFindings(true, printed::add, printed::add);
        assertEquals(List.of(), printed, "the boot audit asked: its Almanac pass reports the finding");
        assertEquals(1, keepsakeFindings(AlmanacValidator.audit()), "the Almanac pass counts it");

        AlmanacKeepsakeCheck.logFindings(false, printed::add, printed::add);
        assertEquals(1, printed.size(), printed.toString());
        assertTrue(printed.get(0).startsWith(AlmanacKeepsakeCheck.LOG_LABEL)
                && printed.get(0).contains(AlmanacKeepsakeCheck.KEEPSAKE_NOT_PICKED), printed.get(0));

        AlmanacSwitch.set(false);
        printed.clear();
        AlmanacKeepsakeCheck.logFindings(false, printed::add, printed::add);
        assertEquals(List.of(), printed, "off means absent");
        assertEquals(0, keepsakeFindings(AlmanacValidator.audit()));
    }

    private static long keepsakeFindings(@Nonnull List<Finding> findings) {
        return findings.stream()
                .filter(finding -> AlmanacKeepsakeCheck.KEEPSAKE_NOT_PICKED.equals(finding.code())
                        || AlmanacKeepsakeCheck.PICKED_NOT_A_KEEPSAKE.equals(finding.code()))
                .count();
    }
}
