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
 * does to an ORDERED array.
 *
 * <p>The load-bearing case is {@code Criteria}. It is an ARRAY rather than a map because progress is
 * keyed by POSITION, and an array is a single leaf as far as inheritance goes - a child that authors
 * Criteria replaces the parent's whole list. That is the deliberate choice (a per-index merge would
 * let a parent edit silently re-point every child's stored progress), so it is proved here rather
 * than assumed.
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

    // ==================== Criteria: the ordered-array decision, proved ====================

    @Nested
    class CriteriaInheritance {

        @Test
        void aChildThatAuthorsNoCriteriaInheritsThemWhole() throws Exception {
            AchievementAsset parent = decodeRoot("""
                    { "Criteria": [ { "Kind": "BREAK_BLOCK", "Target": "Copper_Ore", "Amount": 500 },
                                    { "Kind": "PICKUP_ITEM", "Target": "Copper_Ore", "Amount": 500 } ] }
                    """, "prospector_base");

            AchievementAsset child = decode("""
                    { "Scoring": { "Points": 40 } }
                    """, "prospector", "prospector_base", parent);

            Achievement achievement = child.toDefinition().achievement();
            assertEquals(2, achievement.criteria().size(), "an untouched array carries over whole");
            assertEquals(500L, achievement.criteria().get(0).amount());
            assertEquals(40, achievement.points());
        }

        @Test
        void aChildThatAuthorsCriteriaReplacesTheWholeList() throws Exception {
            AchievementAsset parent = decodeRoot("""
                    { "Criteria": [ { "Kind": "BREAK_BLOCK", "Target": "Copper_Ore", "Amount": 500 },
                                    { "Kind": "PICKUP_ITEM", "Target": "Copper_Ore", "Amount": 500 } ] }
                    """, "prospector_base");

            AchievementAsset child = decode("""
                    { "Criteria": [ { "Kind": "BREAK_BLOCK", "Target": "Iron_Ore", "Amount": 100 } ] }
                    """, "iron_prospector", "prospector_base", parent);

            Achievement achievement = child.toDefinition().achievement();
            assertEquals(1, achievement.criteria().size(),
                    "authoring the array replaces it whole - there is no per-index merge, on purpose");
            assertEquals("Iron_Ore", achievement.criteria().get(0).target());
            assertEquals(100L, achievement.criteria().get(0).amount());
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

    // ==================== The criterion index ====================

    @Test
    void eachCriterionsEngineIdIsItsPositionSoTheIndexIsUnambiguous() throws Exception {
        AchievementAsset asset = decodeRoot("""
                { "Criteria": [ { "Kind": "BREAK_BLOCK", "Target": "A", "Amount": 1, "TextKey": "k.a" },
                                { "Kind": "BREAK_BLOCK", "Target": "B", "Amount": 2 },
                                { "Kind": "BREAK_BLOCK", "Target": "C", "Amount": 3, "TextKey": "k.c" } ] }
                """, "prospector");

        AchievementDefinition definition = asset.toDefinition();
        Achievement achievement = definition.achievement();

        for (int i = 0; i < achievement.criteria().size(); i++) {
            ObjectiveDef criterion = achievement.criteria().get(i);
            assertEquals(String.valueOf(i), criterion.id(),
                    "a criterion's engine id IS its position, which is what progress is keyed by");
            assertEquals(i, achievement.indexOf(criterion.id()));
        }
        assertEquals("k.a", definition.criterionTextKey(0));
        assertNull(definition.criterionTextKey(1), "a criterion with no key carries none");
        assertEquals("k.c", definition.criterionTextKey(2));
    }

    // ==================== The rest of the schema ====================

    @Test
    void theTwoRewardListsAreTheTwoMoments() throws Exception {
        AchievementAsset asset = decodeRoot("""
                { "Criteria": [ { "Kind": "BREAK_BLOCK", "Amount": 1 } ],
                  "Rewards":      [ { "Kind": "yourmod:currency", "Params": { "Id": "coin", "Amount": "50" } } ],
                  "ClaimRewards": [ { "Kind": "yourmod:item", "Params": { "Item": "Sword_Copper" } } ] }
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
                { "Criteria": [ { "Kind": "BREAK_BLOCK", "Amount": 1 } ],
                  "Rewards": [ { "Kind": "yourmod:currency" } ] }
                """, "prospector");
        assertFalse(asset.toDefinition().achievement().requiresClaim());
    }

    @Test
    void unauthoredKnobsTakeTheirDocumentedDefaults() throws Exception {
        AchievementAsset asset = decodeRoot("""
                { "Criteria": [ { "Kind": "BREAK_BLOCK", "Amount": 1 } ] }
                """, "prospector");

        Achievement achievement = asset.toDefinition().achievement();
        assertTrue(achievement.available(), "unauthored Enabled means in circulation");
        assertFalse(achievement.hidden());
        assertTrue(achievement.countsTowardTotal());
        assertEquals(AchievementAsset.Scoring.DEFAULT_POINTS, achievement.points());
        assertFalse(asset.isAbstract());
        assertNull(asset.getOwner());
        assertEquals(0, asset.toDefinition().sortOrder());
    }

    @Test
    void abstractDeliberatelyDoesNotCarryDownToAChild() throws Exception {
        AchievementAsset parent = decodeRoot("""
                { "Abstract": true, "Criteria": [ { "Kind": "BREAK_BLOCK", "Amount": 1 } ] }
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
                { "Criteria": [ { "Kind": "BREAK_BLOCK", "Amount": 1 } ],
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
    void ownerAndTagsRideThroughUntouchedInMeaningButNormalizedInForm() throws Exception {
        AchievementAsset asset = decodeRoot("""
                { "Owner": "YourMod",
                  "Listing": { "Tags": [ "gathering", " ", "capstone" ] },
                  "Criteria": [ { "Kind": "BREAK_BLOCK", "Amount": 1 } ] }
                """, "prospector");

        assertEquals("yourmod", asset.getOwner());
        assertEquals(List.of("gathering", "capstone"), asset.toDefinition().achievement().tags(),
                "a blank tag is dropped rather than carried as an empty classification");
        assertTrue(asset.toDefinition().matchesOwner("YourMod"));
        assertFalse(asset.toDefinition().matchesOwner("othermod"));
    }
}
