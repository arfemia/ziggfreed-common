package com.ziggfreed.common.achievement.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.achievement.AchievementMilestone;
import com.ziggfreed.common.loot.reward.RewardSpec;

/**
 * The two taxonomy file types decode into what a surface and the engine actually read.
 *
 * <p>Fixtures are authored HERE, so nothing in this test depends on content anybody ships or on a
 * number somebody balanced.
 */
class AchievementTaxonomyCodecTest {

    private static AchievementCategoryAsset category(String id, String json) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(AchievementCategoryAsset.class, id, null);
        AchievementCategoryAsset asset = AchievementCategoryAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), null, new AssetExtraInfo<>(data));
        assertNotNull(asset, "category '" + id + "' did not decode");
        return asset;
    }

    private static AchievementMilestoneAsset milestone(String id, String json) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(AchievementMilestoneAsset.class, id, null);
        AchievementMilestoneAsset asset = AchievementMilestoneAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), null, new AssetExtraInfo<>(data));
        assertNotNull(asset, "milestone '" + id + "' did not decode");
        return asset;
    }

    // ==================== categories ====================

    /** A PascalCase filename addresses the same category content writes in lower case. */
    @Test
    void aCategoryIdIsLowerCasedAtDecode() throws IOException {
        AchievementCategoryAsset asset = category("Combat", """
                { "Order": 10, "Icon": "Fixture_Icon", "TitleKey": "fixture.category.combat",
                  "Subcategories": ["melee", "ranged"] }
                """);

        assertEquals("combat", asset.getId());
        assertEquals(Integer.valueOf(10), asset.getOrder());
        assertEquals("Fixture_Icon", asset.getIcon());
        assertEquals("fixture.category.combat", asset.getTitleKey());
        assertEquals(List.of("melee", "ranged"), asset.getSubcategories());
    }

    /** Every leaf is optional, so a file changing one thing says only that thing. */
    @Test
    void aCategoryNamingOnlyAnIconDeclaresNothingElse() throws IOException {
        AchievementCategoryAsset asset = category("Gathering", "{ \"Icon\": \"Fixture_Icon\" }");

        assertEquals("Fixture_Icon", asset.getIcon());
        assertNull(asset.getOrder(), "an unauthored Order is not invented");
        assertNull(asset.getTitleKey());
        assertTrue(asset.getSubcategories().isEmpty());
        assertEquals(Integer.MAX_VALUE, asset.orderOrLast(),
                "a category naming no Order sorts after every category that named one");
    }

    /** Reading order is the taxonomy's own, and stable for two categories that share a sort key. */
    @Test
    void categoriesReadByOrderThenById() {
        AchievementCategoryConfig config = AchievementCategoryConfig.getInstance();
        try {
            config.mergePackLayer(Map.of(
                    "zulu", AchievementCategoryAsset.of("zulu", 5, null, null, null),
                    "alpha", AchievementCategoryAsset.of("alpha", 20, null, null, null),
                    "bravo", AchievementCategoryAsset.of("bravo", 20, null, null, null),
                    "unranked", AchievementCategoryAsset.of("unranked", null, null, null, null)));

            assertEquals(List.of("zulu", "alpha", "bravo", "unranked"),
                    config.ordered().stream().map(AchievementCategoryAsset::getId).toList());
            assertEquals(List.of("zulu", "alpha", "bravo"), config.orderedIds(),
                    "only a category that named an Order takes a place in the declared order");
            assertNotNull(config.category("ZULU"), "a category is addressable in any casing");
            assertNull(config.category("nothing-describes-this"));
        } finally {
            config.mergePackLayer(Map.of());
        }
    }

    // ==================== milestones ====================

    /** A milestone pays out in the shared reward vocabulary: a registered kind plus its parameters. */
    @Test
    void aMilestoneDecodesBothPayoutMoments() throws IOException {
        AchievementMilestoneAsset asset = milestone("Points_Fixture", """
                {
                  "Threshold": 500,
                  "TitleKey": "fixture.milestone.title",
                  "DescriptionKey": "fixture.milestone.desc",
                  "Rewards": {
                    "Auto":  [ { "Kind": "Fixture_Kind", "Params": { "Amount": "7" } } ],
                    "Claim": [ { "Kind": "Item", "Params": { "Item": "Fixture_Item", "Count": "2" } } ]
                  }
                }
                """);

        assertEquals(500, asset.getThreshold());
        assertEquals("fixture.milestone.title", asset.getTitleKey());
        assertEquals("fixture.milestone.desc", asset.getDescriptionKey());

        List<RewardSpec> auto = asset.autoRewards();
        assertEquals(1, auto.size());
        assertEquals("Fixture_Kind", auto.get(0).kind());
        assertEquals("7", auto.get(0).param("Amount"));

        AchievementMilestone runtime = asset.toMilestone();
        assertEquals(500, runtime.threshold());
        assertEquals(1, runtime.autoRewards().size());
        assertEquals(1, runtime.claimRewards().size());
    }

    /** A file with no Rewards block is a rung that marks progress and pays nothing. */
    @Test
    void aMilestoneWithNoRewardsBlockPaysNothing() throws IOException {
        AchievementMilestoneAsset asset = milestone("Points_Bare", "{ \"Threshold\": 100 }");

        assertTrue(asset.autoRewards().isEmpty());
        assertTrue(asset.claimRewards().isEmpty());
        assertEquals(100, asset.toMilestone().threshold());
    }

    /** The threshold is the identity: one rung per number, ascending, whatever the files are called. */
    @Test
    void theLadderIsOneRungPerThresholdAscending() {
        AchievementMilestoneConfig config = AchievementMilestoneConfig.getInstance();
        try {
            config.mergePackLayer(Map.of(
                    "high", AchievementMilestoneAsset.of("high", 900, null, null, null),
                    "low", AchievementMilestoneAsset.of("low", 100, null, null, null),
                    "unreachable", AchievementMilestoneAsset.of("unreachable", 0, null, null, null)));

            List<AchievementMilestone> ladder = config.milestones();
            assertEquals(2, ladder.size(), "a rung reaching nothing is dropped rather than paid at once");
            assertEquals(100, ladder.get(0).threshold());
            assertEquals(900, ladder.get(1).threshold());
        } finally {
            config.mergePackLayer(Map.of());
        }
    }

    /** Two files naming one number are one rung, and the owner layer is the one that stands. */
    @Test
    void anOwnerLayerOutranksAPackRungByRung() {
        AchievementMilestoneConfig config = AchievementMilestoneConfig.getInstance();
        try {
            config.mergePackLayer(Map.of("pack",
                    AchievementMilestoneAsset.of("pack", 250, "fixture.pack", null, null)));
            config.mergeOwnerLayer(Map.of("pack",
                    AchievementMilestoneAsset.of("pack", 250, "fixture.owner", null, null)));

            List<AchievementMilestoneAsset> assets = config.assetsByThreshold();
            assertEquals(1, assets.size(), "one threshold is one rung");
            assertEquals("fixture.owner", assets.get(0).getTitleKey());
        } finally {
            config.mergePackLayer(Map.of());
            config.mergeOwnerLayer(Map.of());
        }
    }
}
