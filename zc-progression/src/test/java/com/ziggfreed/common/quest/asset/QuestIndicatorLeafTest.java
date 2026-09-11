package com.ziggfreed.common.quest.asset;

import static com.ziggfreed.common.quest.asset.QuestAssetCodecTest.decode;
import static com.ziggfreed.common.quest.asset.QuestAssetCodecTest.decodeRoot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.quest.Quest;

/**
 * The {@code Indicator} leaf on a quest file and on one of its steps: it lands on the runtime
 * quest, it survives the definition's own re-stamp, and under native {@code Parent} a child that
 * writes one leaf keeps the rest of what its parent wrote.
 */
class QuestIndicatorLeafTest {

    @Test
    void aQuestsBlockAndAStepsBlockBothLandOnTheRuntimeQuest() throws Exception {
        QuestAsset asset = decodeRoot("""
                { "Npc": { "ViewId": "guide" },
                  "Indicator": { "Available": { "Map": { "Enabled": false } } },
                  "Objectives": {
                    "hand_in": { "Kind": "TURN_IN", "Target": "Copper_Ore", "Amount": 5, "TurnInNpcId": "giver",
                                 "Indicator": { "TurnIn": { "State": "Hand_Me_That" } } },
                    "mine":    { "Kind": "BREAK_BLOCK", "Target": "Copper_Ore", "Amount": 5 } } }
                """, "gather_copper");

        Quest quest = asset.toDefinition(null).quest();
        assertNotNull(quest.indicator(), "the quest's block rides the runtime quest");
        assertFalse(quest.indicator().resolve(QuestSituation.AVAILABLE).showsMap());
        assertNotNull(quest.stepIndicator("hand_in"), "the step's block rides the quest under its step id");
        assertEquals("Hand_Me_That", quest.stepIndicator("hand_in").resolve(QuestSituation.TURN_IN).state());
        assertNull(quest.stepIndicator("mine"), "a step that wrote none has none");
        assertEquals(1, quest.stepIndicators().size());
    }

    @Test
    void aFileWritingNoBlockLeavesTheQuestWithNone() throws Exception {
        Quest quest = decodeRoot("""
                { "Objectives": { "mine": { "Kind": "BREAK_BLOCK", "Target": "Copper_Ore" } } }
                """, "plain").toDefinition(null).quest();
        assertNull(quest.indicator());
        assertTrue(quest.stepIndicators().isEmpty());
    }

    @Test
    void aChildRetuningOneLeafKeepsWhatItsParentWrote() throws Exception {
        QuestAsset parent = decodeRoot("""
                { "Indicator": { "Available": { "State": "Guild_Scroll", "Map": { "Enabled": true, "Icon": "Coordinate.png" } },
                                 "InProgress": { "Overhead": { "Enabled": false } } },
                  "Objectives": { "mine": { "Kind": "BREAK_BLOCK", "Target": "Copper_Ore" } } }
                """, "base");
        QuestAsset child = decode("""
                { "Indicator": { "Available": { "Map": { "Enabled": false } } } }
                """, "child", "base", parent);

        Quest quest = child.toDefinition(null).quest();
        assertNotNull(quest.indicator());
        QuestIndicatorSpec.Resolved available = quest.indicator().resolve(QuestSituation.AVAILABLE);
        assertEquals("Guild_Scroll", available.state(), "the parent's state survives the child's Map edit");
        assertFalse(available.showsMap(), "the child's own leaf wins");
        assertEquals("Coordinate.png", available.mapIcon(), "a sibling leaf of the very group it touched survives");
        assertFalse(quest.indicator().resolve(QuestSituation.IN_PROGRESS).showsOverhead(),
                "a group the child never mentioned survives whole");
    }
}
