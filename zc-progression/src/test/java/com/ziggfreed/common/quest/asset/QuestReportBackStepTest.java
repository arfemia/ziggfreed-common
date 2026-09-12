package com.ziggfreed.common.quest.asset;

import static com.ziggfreed.common.quest.asset.QuestAssetCodecTest.decode;
import static com.ziggfreed.common.quest.asset.QuestAssetCodecTest.decodeRoot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.quest.Quest;

/**
 * The report-back step a quest-level {@code Npc.TurnInId} implies is added by the fold itself: a
 * quest naming a hand-in place and authoring no {@code TURN_IN} step gets one appended after
 * everything else, under the one id saved progress files it by, locked to the resolved place, and
 * worded "go to" only when that place is not the giver. An authored hand-in leaves the quest as
 * written, and a place nobody can be adds nothing.
 */
class QuestReportBackStepTest {

    /** Two ordered gathering steps and a quest-level hand-in place, the ordinary hand-off shape. */
    private static final String HAND_OFF = """
            { "Npc": { "ViewId": "guide", "TurnInId": "%s" },
              "Objectives": { "mine": { "Kind": "BREAK_BLOCK", "Target": "Stone", "Amount": 10, "Order": 1 },
                              "chop": { "Kind": "BREAK_BLOCK", "Target": "Wood", "Amount": 5, "Order": 3 } } }
            """;

    @Test
    void aQuestNamingAHandInPlaceAndNoHandInStepGetsTheReportBackStepLast() throws Exception {
        QuestDefinition definition = decodeRoot(HAND_OFF.formatted("giver"), "gather").toDefinition(null);
        List<ObjectiveDef> steps = definition.quest().objectives();

        assertEquals(3, steps.size(), "the two authored steps and the one the hand-in place implies");
        ObjectiveDef last = steps.get(2);
        assertEquals(QuestAsset.REPORT_BACK_ID, last.id(), "under the one id saved progress files it by");
        assertEquals(QuestObjectiveAsset.HAND_IN_KIND, last.kind());
        assertEquals("", last.target(), "a hand-in of nothing: the walk back is the whole step");
        assertEquals(4, last.order(), "one past the highest authored order, so it waits on every authored step");
        assertEquals("guide", last.turnInLockId(), "'giver' resolved to the quest's own character");
        assertNull(definition.objectiveTextKey(QuestAsset.REPORT_BACK_ID),
                "a return to the giver is the convention line, so no key is stamped");
    }

    @Test
    void aHandInSomewhereElseCarriesTheGoToKey() throws Exception {
        QuestDefinition definition = decodeRoot(HAND_OFF.formatted("quartermaster"), "deliver").toDefinition(null);

        ObjectiveDef last = definition.quest().objective(QuestAsset.REPORT_BACK_ID);
        assertNotNull(last);
        assertEquals("quartermaster", last.turnInLockId());
        assertEquals(QuestAsset.REPORT_BACK_FIND_KEY, definition.objectiveTextKey(QuestAsset.REPORT_BACK_ID),
                "somebody other than the giver reads 'go to', never 'return to'");
    }

    @Test
    void anAuthoredHandInLeavesTheQuestAsWritten() throws Exception {
        Quest quest = decodeRoot("""
                { "Npc": { "ViewId": "guide", "TurnInId": "giver" },
                  "Objectives": { "mine": { "Kind": "BREAK_BLOCK", "Target": "Stone", "Amount": 10, "Order": 1 },
                                  "hand_in": { "Kind": "TURN_IN", "Target": "Stone", "Amount": 10, "Order": 2 } } }
                """, "manual").toDefinition(null).quest();

        assertEquals(2, quest.objectives().size(), "the author wrote the hand-in, so nothing is added");
        assertNull(quest.objective(QuestAsset.REPORT_BACK_ID));
        assertEquals("guide", quest.objective("hand_in").turnInLockId(),
                "and the authored one still takes the quest-level place");
    }

    @Test
    void noHandInPlaceMeansNoExtraStep() throws Exception {
        Quest quest = decodeRoot("""
                { "Npc": { "ViewId": "guide" },
                  "Objectives": { "mine": { "Kind": "BREAK_BLOCK", "Target": "Stone", "Amount": 10 } } }
                """, "passive").toDefinition(null).quest();

        assertEquals(1, quest.objectives().size());
        assertNull(quest.objective(QuestAsset.REPORT_BACK_ID));
    }

    @Test
    void theSentinelWithNobodyToResolveToAddsNothing() throws Exception {
        Quest quest = decodeRoot("""
                { "Npc": { "TurnInId": "giver" },
                  "Objectives": { "mine": { "Kind": "BREAK_BLOCK", "Target": "Stone", "Amount": 10 } } }
                """, "misconfig").toDefinition(null).quest();

        assertEquals(1, quest.objectives().size(),
                "a step locked to nobody could never be finished; the audit names the misconfiguration instead");
        assertNull(quest.objective(QuestAsset.REPORT_BACK_ID));
    }

    @Test
    void unorderedAuthoredStepsPutTheReportBackAtOrderOne() throws Exception {
        Quest quest = decodeRoot("""
                { "Npc": { "ViewId": "guide", "TurnInId": "giver" },
                  "Objectives": { "mine": { "Kind": "BREAK_BLOCK", "Target": "Stone", "Amount": 10 },
                                  "chop": { "Kind": "BREAK_BLOCK", "Target": "Wood", "Amount": 5 } } }
                """, "loose").toDefinition(null).quest();

        ObjectiveDef last = quest.objectives().get(quest.objectives().size() - 1);
        assertEquals(QuestAsset.REPORT_BACK_ID, last.id());
        assertEquals(1, last.order(), "one past the highest authored order, which is none");
    }

    @Test
    void aHandOffWithNoStepsAtAllIsJustTheWalk() throws Exception {
        QuestDefinition definition = decodeRoot("""
                { "Flow": { "AutoAccept": true }, "Npc": { "TurnInId": "guide_wilds" } }
                """, "meet").toDefinition(null);

        List<ObjectiveDef> steps = definition.quest().objectives();
        assertEquals(1, steps.size(), "the hand-in place is what makes it a quest");
        assertEquals(QuestAsset.REPORT_BACK_ID, steps.get(0).id());
        assertEquals("guide_wilds", steps.get(0).turnInLockId());
        assertEquals(1, steps.get(0).order());
        assertEquals(QuestAsset.REPORT_BACK_FIND_KEY, definition.objectiveTextKey(QuestAsset.REPORT_BACK_ID),
                "with no giver at all, the place is somebody to go to");
    }

    @Test
    void aParentChildGetsTheSameStepByConstruction() throws Exception {
        QuestAsset parent = decodeRoot(HAND_OFF.formatted("giver"), "gather_base");
        Quest child = decode("""
                { "Objectives": { "mine": { "Amount": 25 } } }
                """, "gather_copper", "gather_base", parent).toDefinition(null).quest();

        ObjectiveDef last = child.objectives().get(child.objectives().size() - 1);
        assertEquals(QuestAsset.REPORT_BACK_ID, last.id(), "a child retuning one step still walks back");
        assertEquals(4, last.order());
        assertEquals("guide", last.turnInLockId());
        assertTrue(child.objectives().stream().filter(o -> QuestAsset.REPORT_BACK_ID.equals(o.id())).count() == 1,
                "and gets it exactly once");
    }
}
