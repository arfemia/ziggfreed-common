package com.ziggfreed.common.achievement.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.BooleanSchema;
import com.hypixel.hytale.codec.schema.config.ObjectSchema;
import com.hypixel.hytale.codec.schema.config.StringSchema;
import com.ziggfreed.common.achievement.Achievement;

/**
 * Two listing facts an achievement file now states for itself: that it is a feat of strength (its
 * own earned-only section rather than the browse list), and the version that retired it. Both are
 * listing leaves only, so neither touches whether the achievement's points count.
 */
class AchievementDistinctionsTest {

    private static final String ONE_STEP =
            "\"Criteria\": { \"one\": { \"Kind\": \"BREAK_BLOCK\", \"Target\": \"Fixture_Block\", \"Amount\": 1 } }";

    @Test
    void aListingFeatMakesAFeatOfStrength() throws Exception {
        AchievementAsset asset = AchievementAssetCodecTest.decodeRoot(
                "{ \"Listing\": { \"Category\": \"fixture\", \"Feat\": true }, " + ONE_STEP + " }",
                "fixture_feat");

        assertTrue(asset.toDefinition().achievement().featOfStrength());
    }

    @Test
    void anUnauthoredFeatIsAnOrdinaryAchievement() throws Exception {
        AchievementAsset asset = AchievementAssetCodecTest.decodeRoot(
                "{ \"Listing\": { \"Category\": \"fixture\" }, " + ONE_STEP + " }", "fixture_plain");

        Achievement achievement = asset.toDefinition().achievement();
        assertFalse(achievement.featOfStrength());
        assertNull(achievement.legacySince());
    }

    @Test
    void aListingLegacySinceNamesTheVersionItRetiredIn() throws Exception {
        AchievementAsset asset = AchievementAssetCodecTest.decodeRoot(
                "{ \"Listing\": { \"Feat\": true, \"LegacySince\": \" 1.7.0 \" }, " + ONE_STEP + " }",
                "fixture_retired");

        assertEquals("1.7.0", asset.toDefinition().achievement().legacySince(), "trimmed as written");
    }

    @Test
    void bothLeavesInheritThroughParentLeafByLeaf() throws Exception {
        AchievementAsset parent = AchievementAssetCodecTest.decodeRoot(
                "{ \"Listing\": { \"Category\": \"fixture\", \"Feat\": true, \"LegacySince\": \"1.6.0\" } }",
                "fixture_base");
        AchievementAsset child = AchievementAssetCodecTest.decode(
                "{ \"Listing\": { \"LegacySince\": \"1.7.0\" }, " + ONE_STEP + " }",
                "fixture_child", "fixture_base", parent);

        Achievement achievement = child.toDefinition().achievement();
        assertTrue(achievement.featOfStrength(), "a leaf the child did not author is inherited");
        assertEquals("1.7.0", achievement.legacySince(), "the child's own leaf wins");
        assertEquals("fixture", achievement.category(), "and a sibling leaf of the group survives");
    }

    @Test
    void aFeatLeavesWhetherItsPointsCountToScoring() throws Exception {
        AchievementAsset counted = AchievementAssetCodecTest.decodeRoot(
                "{ \"Listing\": { \"Feat\": true }, " + ONE_STEP + " }", "fixture_counted_feat");
        AchievementAsset uncounted = AchievementAssetCodecTest.decodeRoot(
                "{ \"Listing\": { \"Feat\": true }, \"Scoring\": { \"CountsTowardTotal\": false }, "
                        + ONE_STEP + " }", "fixture_uncounted_feat");

        assertTrue(counted.toDefinition().achievement().countsTowardTotal(),
                "a feat changes where it is listed, never what Scoring says");
        assertFalse(uncounted.toDefinition().achievement().countsTowardTotal());
    }

    @Test
    void theListingSchemaOffersBothLeavesWithTheirEffectiveDefault() {
        ObjectSchema schema = AchievementAsset.Listing.CODEC.toSchema(new SchemaContext());

        BooleanSchema feat = (BooleanSchema) schema.getProperties().get("Feat");
        assertEquals(Boolean.FALSE, feat.getDefault(),
                "unauthored Feat is false, and the editor must not draw a box that lies about it");
        assertTrue(schema.getProperties().get("LegacySince") instanceof StringSchema);
    }
}
