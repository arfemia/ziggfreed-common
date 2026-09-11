package com.ziggfreed.common.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.quest.asset.QuestIndicatorSpec;

/**
 * The two copies a {@link Quest} makes of itself carry every flow flag across.
 *
 * <p>{@link Quest#withTurnInAt} and {@link Quest#withAuthoring} are the only way a built quest is
 * re-stamped, and each is a hand-written field list: a flag added to the builder and missed in one
 * of them silently reads false after the copy, on every quest a consumer stamped a site or its
 * authoring onto. Pinning them here means that omission fails the build instead of a screen.
 */
class QuestCopyTest {

    private static final QuestIndicatorSpec QUEST_BLOCK = QuestIndicatorSpec.of(false, null, null, null, null);
    private static final QuestIndicatorSpec STEP_BLOCK = QuestIndicatorSpec.of(null, null,
            QuestIndicatorSpec.Situation.of(null, "Hand_Me_That", null, null), null, null);

    private static Quest flagged() {
        return Quest.builder("q_copy")
                .objective(ObjectiveDef.builder("step", "BREAK_BLOCK").target("Oak_Log").amount(1).build())
                .sequential(true)
                .hideLockedSteps(true)
                .autoAccept(true)
                .autoTrack(true)
                .tags(List.of("tutorial"))
                .indicator(QUEST_BLOCK)
                .stepIndicator("step", STEP_BLOCK)
                .build();
    }

    private static void assertFlagsCarried(Quest copy) {
        assertTrue(copy.sequential(), "sequential");
        assertTrue(copy.hideLockedSteps(), "hideLockedSteps");
        assertTrue(copy.autoAccept(), "autoAccept");
        assertTrue(copy.autoTrack(), "autoTrack");
        assertEquals(List.of("tutorial"), copy.tags());
        assertEquals(1, copy.objectives().size());
        assertSame(QUEST_BLOCK, copy.indicator(), "the quest's Indicator block");
        assertSame(STEP_BLOCK, copy.stepIndicator("step"), "a step's Indicator block");
    }

    @Test
    void reStampingTheCollectionSiteKeepsEveryFlowFlag() {
        assertFlagsCarried(flagged().withTurnInAt(QuestTurnInSite.character("guide")));
    }

    @Test
    void reStampingTheAuthoringKeepsEveryFlowFlag() {
        assertFlagsCarried(flagged().withAuthoring(null, null, "guide", 5, "main", null));
    }
}
