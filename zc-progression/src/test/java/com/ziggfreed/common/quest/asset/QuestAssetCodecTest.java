package com.ziggfreed.common.quest.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.progress.MatchMode;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.validation.Finding;

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
                      "Repeat":     { "Cooldown": { "Hours": 24 } },
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
            assertTrue(child.quest().repeatable());
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

    // ==================== repeat ====================

    @Nested
    class RepeatRules {

        @Test
        void anEmptyBlockIsAnExternallyGovernedRepeatable() throws Exception {
            Quest quest = decodeRoot("{ \"Repeat\": { } }", "governed").toDefinition(null).quest();

            assertTrue(quest.repeatable(), "authoring the block at all is what makes it repeatable");
            assertEquals(Quest.Repeat.EXTERNALLY_GOVERNED, quest.repeat(),
                    "nothing inside means nothing holds it back");
        }

        @Test
        void aCalendarWindowNeedsNoCooldownBesideIt() throws Exception {
            Quest.Repeat repeat = decodeRoot("""
                    { "Repeat": { "Reset": { "Period": "Daily" } } }
                    """, "daily").toDefinition(null).quest().repeat();

            assertNotNull(repeat);
            assertEquals(0L, repeat.cooldownMs(), "no rolling wait was authored");
            assertNotNull(repeat.reset());
            assertEquals(Quest.Repeat.Reset.Period.DAILY, repeat.reset().period());
            assertEquals(1, repeat.reset().times(), "unauthored Times means once per window");
        }

        @Test
        void theDurationUnitsAddUp() throws Exception {
            Quest.Repeat repeat = decodeRoot("""
                    { "Repeat": { "Cooldown": { "Hours": 1, "Minutes": 30 } } }
                    """, "ninety").toDefinition(null).quest().repeat();

            assertNotNull(repeat);
            assertEquals(5_400_000L, repeat.cooldownMs());
        }

        @Test
        void everyLeafInheritsRightDownIntoTheNestedWindow() throws Exception {
            QuestAsset parent = decodeRoot("""
                    { "Repeat": { "Cooldown": { "Hours": 24 }, "CooldownFrom": "Complete",
                                  "Reset": { "Period": "Weekly", "Weekday": "Sunday", "Times": 3 } } }
                    """, "base");

            Quest.Repeat child = decode("""
                    { "Repeat": { "MaxCompletions": 5, "Reset": { "Period": "Daily" } } }
                    """, "child", "base", parent).toDefinition(null).quest().repeat();

            assertNotNull(child);
            assertEquals(86_400_000L, child.cooldownMs(), "an untouched leaf carries down");
            assertEquals(Quest.Repeat.CooldownFrom.COMPLETE, child.cooldownFrom());
            assertEquals(5, child.maxCompletions(), "the child's own leaf lands");
            assertNotNull(child.reset());
            assertEquals(Quest.Repeat.Reset.Period.DAILY, child.reset().period(),
                    "the child's own window leaf wins");
            assertEquals(3, child.reset().times(),
                    "inheritance reaches leaf by leaf right into the nested window, so a child that"
                            + " retunes one knob keeps the rest of the parent's - the same rule the"
                            + " whole schema keeps");
        }

        @Test
        void anUnknownEnumFallsBackAndTheValidatorSaysSo() throws Exception {
            QuestAsset asset = decodeRoot("""
                    { "Repeat": { "CooldownFrom": "Whenever",
                                  "Reset": { "Period": "Fortnightly", "Weekday": "Someday" } } }
                    """, "typo");

            Quest.Repeat folded = asset.toDefinition(null).quest().repeat();
            assertNotNull(folded);
            assertEquals(Quest.Repeat.CooldownFrom.CLAIM, folded.cooldownFrom(),
                    "a typo must not take a whole quest out of circulation");
            assertNotNull(folded.reset());
            assertEquals(Quest.Repeat.Reset.Period.DAILY, folded.reset().period());

            List<String> codes = new ArrayList<>();
            for (Finding finding : QuestPoolValidator.repeatFindings(asset.getRepeat(), "typo")) {
                codes.add(finding.code());
            }
            assertTrue(codes.contains("REPEAT_UNKNOWN_COOLDOWN_FROM"));
            assertTrue(codes.contains("REPEAT_UNKNOWN_PERIOD"));
            assertTrue(codes.contains("REPEAT_UNKNOWN_WEEKDAY"));
        }
    }

    // ==================== completion conversation ====================

    @Nested
    class CompletionConversation {

        @Test
        void theCompletionConversationInheritsFromAParent() throws Exception {
            QuestAsset parent = decodeRoot("{ \"CompletionDialogue\": \"guide_thanks\" }", "base");

            assertEquals("guide_thanks", decode("{ \"Enabled\": true }", "child", "base", parent)
                    .toDefinition(null).completionDialogue());
        }

        @Test
        void anEmptyCompletionConversationClearsTheInheritedOne() throws Exception {
            QuestAsset parent = decodeRoot("{ \"CompletionDialogue\": \"guide_thanks\" }", "base");

            assertNull(decode("{ \"CompletionDialogue\": \"\" }", "quiet", "base", parent)
                            .toDefinition(null).completionDialogue(),
                    "an authored empty string is how a child says it does not talk afterwards");
        }

        @Test
        void theCompletionConversationIsLowerCasedAndTrimmedAtOneAuthority() throws Exception {
            assertEquals("guide_thanks",
                    decodeRoot("{ \"CompletionDialogue\": \"  Guide_Thanks  \" }", "loud")
                            .toDefinition(null).completionDialogue());
        }

        @Test
        void namingNoConversationIsTheOrdinaryCase() throws Exception {
            assertNull(decodeRoot("{ }", "plain").toDefinition(null).completionDialogue());
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
            assertNull(definition.quest().repeat(), "no Repeat block at all is a one-shot");
            assertFalse(definition.quest().repeatable());
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
