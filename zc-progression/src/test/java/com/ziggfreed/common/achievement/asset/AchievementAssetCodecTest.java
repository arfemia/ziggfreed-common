package com.ziggfreed.common.achievement.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.progress.ObjectiveDef;

/**
 * {@link AchievementAsset}'s decode contract, and above all what native {@code Parent} inheritance
 * does to the {@code Criteria} map.
 *
 * <p>The load-bearing case is {@code Criteria}: a map keyed by criterion id, merged PER KEY under
 * inheritance exactly like a quest's {@code Objectives} - a child may retune one criterion by key
 * and keeps every criterion it did not mention, and the KEY is what progress is stored under, so a
 * re-authoring can never silently re-point a player's tally. Proved here rather than assumed.
 */
class AchievementAssetCodecTest {

    static AchievementAsset decode(String json, String id, String parentId, AchievementAsset parent)
            throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(AchievementAsset.class, id, parentId);
        return AchievementAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), parent, new AssetExtraInfo<>(data));
    }

    static AchievementAsset decodeRoot(String json, String id) throws IOException {
        return decode(json, id, null, null);
    }

    // ==================== Criteria: the keyed-map decision, proved ====================

    @Nested
    class CriteriaInheritance {

        @Test
        void aChildThatAuthorsNoCriteriaInheritsThemWhole() throws Exception {
            AchievementAsset parent = decodeRoot("""
                    { "Criteria": { "mine":   { "Kind": "BREAK_BLOCK", "Target": "Copper_Ore", "Amount": 500 },
                                    "gather": { "Kind": "PICKUP_ITEM", "Target": "Copper_Ore", "Amount": 500 } } }
                    """, "prospector_base");

            AchievementAsset child = decode("""
                    { "Scoring": { "Points": 40 } }
                    """, "prospector", "prospector_base", parent);

            Achievement achievement = child.toDefinition().achievement();
            assertEquals(2, achievement.criteria().size(), "an untouched map carries over whole");
            assertEquals(500L, achievement.criteria().get(0).amount());
            assertEquals(40, achievement.points());
        }

        @Test
        void aChildRetunesOneCriterionByKeyAndKeepsTheRest() throws Exception {
            AchievementAsset parent = decodeRoot("""
                    { "Criteria": { "mine":   { "Kind": "BREAK_BLOCK", "Target": "Copper_Ore", "Amount": 500 },
                                    "gather": { "Kind": "PICKUP_ITEM", "Target": "Copper_Ore", "Amount": 500 } } }
                    """, "prospector_base");

            AchievementAsset child = decode("""
                    { "Criteria": { "mine": { "Target": "Iron_Ore", "Amount": 100 } } }
                    """, "iron_prospector", "prospector_base", parent);

            Achievement achievement = child.toDefinition().achievement();
            assertEquals(2, achievement.criteria().size(),
                    "the map merges per key: the untouched criterion survives");
            int mine = achievement.indexOf("mine");
            assertEquals("Iron_Ore", achievement.criteria().get(mine).target(),
                    "the child's own leaf wins inside the criterion it retuned");
            assertEquals(100L, achievement.criteria().get(mine).amount());
            assertEquals("BREAK_BLOCK", achievement.criteria().get(mine).kind(),
                    "a leaf the child did not author is inherited from the parent's criterion");
            int gather = achievement.indexOf("gather");
            assertEquals(500L, achievement.criteria().get(gather).amount());
        }

        @Test
        void everyOtherGroupStillMergesLeafByLeaf() throws Exception {
            AchievementAsset parent = decodeRoot("""
                    { "Text": { "TitleKey": "yourmod.base.title", "FlavorKey": "yourmod.base.flavor" },
                      "Listing": { "Category": "gathering", "SortOrder": 10, "Icon": "Copper_Ore" },
                      "Scoring": { "Points": 20, "CountsTowardTotal": true } }
                    """, "prospector_base");

            AchievementAsset child = decode("""
                    { "Text": { "TitleKey": "yourmod.iron.title" },
                      "Listing": { "Icon": "Iron_Ore" } }
                    """, "iron_prospector", "prospector_base", parent);

            AchievementDefinition definition = child.toDefinition();
            assertEquals("yourmod.iron.title", definition.titleKey(), "the child's own key wins");
            assertEquals("yourmod.base.flavor", definition.flavorKey(),
                    "a sibling leaf of the very group it touched survives");
            assertEquals("gathering", definition.category());
            assertEquals(10, definition.sortOrder());
            assertEquals("Iron_Ore", definition.icon());
            assertEquals(20, definition.achievement().points());
        }
    }

    // ==================== The criterion id ====================

    @Test
    void eachCriterionsEngineIdIsItsAuthoredKeyWhichIsWhatProgressIsKeyedBy() throws Exception {
        AchievementAsset asset = decodeRoot("""
                { "Criteria": { "a": { "Kind": "BREAK_BLOCK", "Target": "A", "Amount": 1, "TextKey": "k.a" },
                                "b": { "Kind": "BREAK_BLOCK", "Target": "B", "Amount": 2 },
                                "c": { "Kind": "BREAK_BLOCK", "Target": "C", "Amount": 3, "TextKey": "k.c" } } }
                """, "prospector");

        AchievementDefinition definition = asset.toDefinition();
        Achievement achievement = definition.achievement();

        assertEquals(3, achievement.criteria().size());
        List<String> ids = achievement.criteria().stream().map(ObjectiveDef::id).toList();
        assertEquals(List.of("a", "b", "c"), ids,
                "criteria keep authored order, and each engine id is the authored KEY");
        assertEquals(1, achievement.indexOf("b"));
        assertEquals("k.a", definition.criterionTextKey("a"));
        assertNull(definition.criterionTextKey("b"), "a criterion with no key carries none");
        assertEquals("k.c", definition.criterionTextKey("c"));
    }

    // ==================== The rest of the schema ====================

    @Test
    void theTwoRewardBucketsAreTheTwoMoments() throws Exception {
        AchievementAsset asset = decodeRoot("""
                { "Criteria": { "step": { "Kind": "BREAK_BLOCK", "Amount": 1 } },
                  "Rewards": { "Auto":  [ { "Kind": "yourmod:currency", "Params": { "Id": "coin", "Amount": "50" } } ],
                               "Claim": [ { "Kind": "yourmod:item", "Params": { "Item": "Sword_Copper" } } ] } }
                """, "prospector");

        Achievement achievement = asset.toDefinition().achievement();
        assertEquals(1, achievement.autoRewards().size());
        assertEquals("yourmod:currency", achievement.autoRewards().get(0).kind());
        assertEquals("50", achievement.autoRewards().get(0).param("Amount"));
        assertEquals(1, achievement.claimRewards().size());
        assertTrue(achievement.requiresClaim(), "authoring a claim reward is what makes it wait");
    }

    @Test
    void anAchievementWithNoClaimRewardsHasNothingToComeBackFor() throws Exception {
        AchievementAsset asset = decodeRoot("""
                { "Criteria": { "step": { "Kind": "BREAK_BLOCK", "Amount": 1 } },
                  "Rewards": { "Auto": [ { "Kind": "yourmod:currency" } ] } }
                """, "prospector");
        assertFalse(asset.toDefinition().achievement().requiresClaim());
    }

    @Test
    void unauthoredKnobsTakeTheirDocumentedDefaults() throws Exception {
        AchievementAsset asset = decodeRoot("""
                { "Criteria": { "step": { "Kind": "BREAK_BLOCK", "Amount": 1 } } }
                """, "prospector");

        Achievement achievement = asset.toDefinition().achievement();
        assertTrue(achievement.available(), "unauthored Enabled means in circulation");
        assertFalse(achievement.hidden());
        assertTrue(achievement.countsTowardTotal());
        assertEquals(AchievementAsset.Scoring.DEFAULT_POINTS, achievement.points());
        assertFalse(asset.isAbstract());
        assertEquals(0, asset.toDefinition().sortOrder());
    }

    @Test
    void abstractDeliberatelyDoesNotCarryDownToAChild() throws Exception {
        AchievementAsset parent = decodeRoot("""
                { "Abstract": true, "Criteria": { "step": { "Kind": "BREAK_BLOCK", "Amount": 1 } } }
                """, "prospector_base");
        assertTrue(parent.isAbstract());

        AchievementAsset child = decode("{ }", "prospector", "prospector_base", parent);
        assertFalse(child.isAbstract(), "inheriting from a skeleton makes a real achievement");
    }

    @Test
    void aCapstoneNamesItsChildrenAndNeedsNoCriteria() throws Exception {
        AchievementAsset asset = decodeRoot("""
                { "MetaChildren": [ "One", "two" ] }
                """, "capstone");

        Achievement achievement = asset.toDefinition().achievement();
        assertTrue(achievement.isMeta());
        assertEquals(List.of("one", "two"), achievement.metaChildren(),
                "child ids are lower-cased to match how every id is addressed");
        assertTrue(achievement.criteria().isEmpty());
    }

    @Test
    void aRequiresBlockDecodesThroughTheSharedGateSchema() throws Exception {
        AchievementAsset asset = decodeRoot("""
                { "Criteria": { "step": { "Kind": "BREAK_BLOCK", "Amount": 1 } },
                  "Requires": { "Permission": "yourmod.ach.advanced",
                                "Factors": [ { "Factor": "yourmod:rank", "Min": 5 } ] } }
                """, "prospector");

        AchievementDefinition definition = asset.toDefinition();
        assertNotNull(definition.requires());
        assertFalse(definition.requires().isEmpty());
        assertEquals("yourmod.ach.advanced", definition.requires().getPermission());
        assertEquals(1, definition.requires().factorsOrEmpty().length);
    }

    @Test
    void tagsRideThroughUntouchedInMeaningButNormalizedInForm() throws Exception {
        AchievementAsset asset = decodeRoot("""
                { "Listing": { "Tags": [ "gathering", " ", "capstone" ] },
                  "Criteria": { "step": { "Kind": "BREAK_BLOCK", "Amount": 1 } } }
                """, "prospector");

        assertEquals(List.of("gathering", "capstone"), asset.toDefinition().achievement().tags(),
                "a blank tag is dropped rather than carried as an empty classification");
    }
}
