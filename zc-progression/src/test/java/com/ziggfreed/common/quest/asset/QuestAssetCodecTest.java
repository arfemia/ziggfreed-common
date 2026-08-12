package com.ziggfreed.common.quest.asset;

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
import com.ziggfreed.common.progress.MatchMode;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.quest.Quest;

/**
 * {@link QuestAsset}'s decode contract, and above all what native {@code Parent} inheritance does to
 * it.
 *
 * <p>The load-bearing case is {@code Objectives}: it is a MAP rather than an array precisely so a
 * child quest can retune ONE step and keep its siblings. An array would be a single leaf as far as
 * inheritance goes, so authoring it at all would drop every inherited step - which is the whole
 * reason for the map, and is therefore proved here rather than assumed.
 */
class QuestAssetCodecTest {

    static QuestAsset decode(String json, String id, String parentId, QuestAsset parent) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(QuestAsset.class, id, parentId);
        return QuestAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), parent, new AssetExtraInfo<>(data));
    }

    static QuestAsset decodeRoot(String json, String id) throws IOException {
        return decode(json, id, null, null);
    }

    // ==================== Objectives: the map-vs-array decision, proved ====================

    @Nested
    class ObjectiveInheritance {

        @Test
        void aChildRetuningOneObjectiveKeepsItsSiblings() throws Exception {
            QuestAsset parent = decodeRoot("""
                    { "Objectives": { "collect": { "Kind": "PICKUP_ITEM", "Target": "Copper_Ore", "Amount": 10 },
                                      "hand_in": { "Kind": "TURN_IN", "Target": "Copper_Ore", "Amount": 10,
                                                   "Order": 2 } } }
                    """, "gather_base");

            QuestAsset child = decode("""
                    { "Objectives": { "collect": { "Amount": 25 } } }
                    """, "gather_copper", "gather_base", parent);

            QuestDefinition definition = child.toDefinition(null);
            ObjectiveDef collect = definition.quest().objective("collect");
            ObjectiveDef handIn = definition.quest().objective("hand_in");

            assertNotNull(collect);
            assertEquals(25L, collect.amount(), "the child's own number must win");
            assertEquals("Copper_Ore", collect.target(),
                    "a sibling leaf of the very step it touched must survive");
            assertNotNull(handIn, "a step the child never mentioned must survive - this is what the map buys");
            assertEquals(10L, handIn.amount());
            assertEquals(2, handIn.order());
        }

        @Test
        void aChildMayAddAnObjectiveTheParentNeverHad() throws Exception {
            QuestAsset parent = decodeRoot("""
                    { "Objectives": { "collect": { "Kind": "PICKUP_ITEM", "Target": "Copper_Ore" } } }
                    """, "gather_base");

            QuestAsset child = decode("""
                    { "Objectives": { "escort": { "Kind": "TALK_TO_NPC", "Target": "guide" } } }
                    """, "gather_plus", "gather_base", parent);

            Quest quest = child.toDefinition(null).quest();
            assertEquals(2, quest.objectives().size());
            assertNotNull(quest.objective("collect"));
            assertNotNull(quest.objective("escort"));
        }

        @Test
        void everyGroupInheritsLeafByLeaf() throws Exception {
            QuestAsset parent = decodeRoot("""
                    { "Text":       { "TitleKey": "quest.base.title", "FlavorKey": "quest.base.flavor" },
                      "Listing":    { "Category": "gathering", "SortOrder": 10, "Tags": ["daily"] },
                      "Flow":       { "AutoTrack": true, "AutoClaim": false },
                      "Repeat":     { "Repeatable": true, "CooldownSeconds": 86400 },
                      "Visibility": { "RequirePrerequisites": true },
                      "Npc":        { "ViewId": "guide", "TurnInId": "giver" } }
                    """, "base");

            QuestDefinition child = decode("""
                    { "Text": { "TitleKey": "quest.child.title" } }
                    """, "child", "base", parent).toDefinition(null);

            assertEquals("quest.child.title", child.titleKey(), "the child's own leaf wins");
            assertEquals("quest.base.flavor", child.flavorKey(), "its untouched sibling survives");
            assertEquals("gathering", child.category(), "and so does every other group");
            assertEquals(10, child.sortOrder());
            assertEquals(List.of("daily"), child.quest().tags());
            assertTrue(child.quest().autoTrack());
            assertFalse(child.quest().autoClaim());
            assertTrue(child.quest().repeat().repeatable());
            assertEquals(86_400_000L, child.quest().repeat().cooldownMs());
            assertTrue(child.quest().visibility().requirePrerequisites());
            assertEquals("guide", child.npcViewId());
        }

        @Test
        void rewardsAreOneLeafSoAuthoringThemReplacesTheInheritedList() throws Exception {
            QuestAsset parent = decodeRoot("""
                    { "Rewards": [ { "Kind": "yourmod:currency", "Params": { "Amount": "50" } },
                                   { "Kind": "yourmod:item", "Params": { "Item": "Torch" } } ] }
                    """, "base");

            assertEquals(2, decode("{ \"Enabled\": true }", "silent", "base", parent)
                            .toDefinition(null).quest().rewards().size(),
                    "a child that never mentions Rewards inherits the whole list");

            assertEquals(1, decode("""
                            { "Rewards": [ { "Kind": "yourmod:item", "Params": { "Item": "Lantern" } } ] }
                            """, "loud", "base", parent).toDefinition(null).quest().rewards().size(),
                    "Rewards is ONE leaf, so authoring it replaces the inherited list rather than adding to it");

            assertTrue(decode("{ \"Rewards\": [] }", "empty", "base", parent)
                            .toDefinition(null).quest().rewards().isEmpty(),
                    "an authored empty array is how a child inherits a quest and pays out nothing");
        }
    }

    // ==================== defaults ====================

    @Nested
    class Defaults {

        @Test
        void anEmptyFileIsAnOpenOneShotQuestNobodyCanFinishYet() throws Exception {
            QuestDefinition definition = decodeRoot("{ }", "bare").toDefinition(null);

            assertTrue(definition.quest().available(), "unauthored Enabled means in circulation");
            assertTrue(definition.quest().autoClaim(), "unauthored AutoClaim matches the engine default");
            assertFalse(definition.quest().autoAccept());
            assertFalse(definition.quest().autoTrack());
            assertFalse(definition.quest().sequential());
            assertFalse(definition.quest().repeat().repeatable());
            assertEquals(Quest.Visibility.OPEN, definition.quest().visibility());
            assertTrue(definition.quest().objectives().isEmpty());
            assertTrue(definition.requires().isEmpty(), "an unauthored Requires block asks for nothing");
            assertEquals(0, definition.sortOrder());
            assertNull(definition.owner());
            assertFalse(definition.isGenerated());
        }

        @Test
        void anObjectiveKeepsTheEnginesOwnParseDefaults() throws Exception {
            ObjectiveDef objective = decodeRoot("""
                    { "Objectives": { "collect": { "Kind": "PICKUP_ITEM", "Target": "Copper" } } }
                    """, "q").toDefinition(null).quest().objective("collect");

            assertNotNull(objective);
            assertEquals(MatchMode.CONTAINS, objective.matchMode(), "unauthored MatchMode is the forgiving one");
            assertEquals(1L, objective.amount(), "unauthored Amount is one");
            assertEquals(0, objective.order());
            assertNull(objective.zone());
            assertNull(objective.qualifier());
        }

        @Test
        void anAuthoredMatchModeIsParsedCaseInsensitivelyAndBadInputFallsBack() throws Exception {
            QuestAsset asset = decodeRoot("""
                    { "Objectives": { "a": { "Kind": "BREAK_BLOCK", "MatchMode": "exact" },
                                      "b": { "Kind": "BREAK_BLOCK", "MatchMode": "nonsense" } } }
                    """, "q");
            Quest quest = asset.toDefinition(null).quest();
            assertEquals(MatchMode.EXACT, quest.objective("a").matchMode());
            assertEquals(MatchMode.CONTAINS, quest.objective("b").matchMode(),
                    "an unreadable mode falls back rather than refusing to load the quest");
        }

        @Test
        void abstractQuestsAreStillOrdinaryAssetsTheyAreJustNeverOffered() throws Exception {
            assertTrue(decodeRoot("{ \"Abstract\": true }", "base").isAbstract());
            assertFalse(decodeRoot("{ }", "base").isAbstract());
        }

        @Test
        void abstractIsTheOneFieldThatMustNotCarryDownToAChild() throws Exception {
            QuestAsset base = decodeRoot("""
                    { "Abstract": true, "Owner": "yourmod",
                      "Objectives": { "collect": { "Kind": "PICKUP_ITEM", "Target": "Ore" } } }
                    """, "base");

            QuestAsset child = decode("{ \"Text\": { \"TitleKey\": \"q.title\" } }", "child", "base", base);

            assertFalse(child.isAbstract(),
                    "inheriting from a skeleton makes a real quest; if this carried down, every child of "
                            + "every base would silently vanish from the pool");
            assertEquals("yourmod", child.getOwner(), "everything else still inherits");
        }

        @Test
        void anAuthoringCommentIsIgnoredAtEveryLevel() throws Exception {
            QuestDefinition definition = decodeRoot("""
                    { "$Comment": "the tier one gathering quest",
                      "Text": { "$Comment": "keys resolve on the player's own client",
                                "TitleKey": "quest.q.title" },
                      "Objectives": { "$Comment": "one step",
                                      "collect": { "Kind": "PICKUP_ITEM", "Target": "Copper" } } }
                    """, "q").toDefinition(null);

            assertEquals("quest.q.title", definition.titleKey());
            assertEquals(1, definition.quest().objectives().size(),
                    "a comment key inside the objective map must not become an objective");
        }
    }

    // ==================== hand-in resolution ====================

    @Nested
    class HandIn {

        @Test
        void theGiverSentinelResolvesToTheQuestsOwnNpc() throws Exception {
            Quest quest = decodeRoot("""
                    { "Npc": { "ViewId": "guide" },
                      "Objectives": { "hand_in": { "Kind": "TURN_IN", "Target": "Ore",
                                                   "TurnInNpcId": "GIVER" } } }
                    """, "q").toDefinition(null).quest();

            assertEquals("guide", quest.objective("hand_in").turnInLockId());
        }

        @Test
        void aStepWithNoHandInPlaceFallsBackToTheQuestsOwn() throws Exception {
            Quest quest = decodeRoot("""
                    { "Npc": { "ViewId": "guide", "TurnInId": "giver" },
                      "Objectives": { "hand_in": { "Kind": "TURN_IN", "Target": "Ore" },
                                      "elsewhere": { "Kind": "TURN_IN", "Target": "Ore",
                                                     "TurnInNpcId": "quartermaster" },
                                      "anywhere": { "Kind": "TURN_IN", "Target": "Ore",
                                                    "TurnInNpcId": "" } } }
                    """, "q").toDefinition(null).quest();

            assertEquals("guide", quest.objective("hand_in").turnInLockId(),
                    "the quest says where it is handed in once");
            assertEquals("quartermaster", quest.objective("elsewhere").turnInLockId(),
                    "a step may still send itself somewhere else");
            assertNull(quest.objective("anywhere").turnInLockId(),
                    "an explicitly empty place is how one step opts out of the quest-level surface");
        }

        @Test
        void theSentinelWithNoNpcLeavesTheStepOpenRatherThanLockedToNothing() throws Exception {
            Quest quest = decodeRoot("""
                    { "Objectives": { "hand_in": { "Kind": "TURN_IN", "Target": "Ore", "TurnInNpcId": "giver" } } }
                    """, "q").toDefinition(null).quest();

            assertNull(quest.objective("hand_in").turnInLockId());
        }
    }
}
