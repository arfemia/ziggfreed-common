package com.ziggfreed.common.settings;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.feedback.moment.FeedbackEngine;

/**
 * The one closed vocabulary a player picks for their quest and achievement notices, and how it grades a
 * marked toast by what the moment carries: a progress tick (current and required), a finish, the engine's
 * own answer to the authored mark, and anything that is no tick at all (a completion, a claim, an unlock).
 */
class NotificationLevelTest {

    private static final Map<String, Object> TICK = Map.of(FeedbackEngine.CURRENT_ARG, 1,
            FeedbackEngine.REQUIRED_ARG, 8, FeedbackEngine.FINISHED_ARG, false);
    private static final Map<String, Object> FINISH = Map.of(FeedbackEngine.CURRENT_ARG, 8,
            FeedbackEngine.REQUIRED_ARG, 8, FeedbackEngine.FINISHED_ARG, true);
    private static final Map<String, Object> DONE = Map.of("title", "A quest");

    @Test
    void theFourWordsReadBackIgnoringCase() {
        assertArrayEquals(new String[] {"EveryUpdate", "Milestones", "Finishes", "None"}, NotificationLevel.ids());
        assertEquals(NotificationLevel.MILESTONES, NotificationLevel.parse(" milestones "));
        assertEquals(NotificationLevel.EVERY_UPDATE, NotificationLevel.parse("EVERYUPDATE"));
        assertNull(NotificationLevel.parse("Loud"), "a word nobody knows is no choice");
        assertNull(NotificationLevel.parse(" "));
        assertNull(NotificationLevel.parse(null));
        assertEquals(NotificationLevel.EVERY_UPDATE, NotificationLevel.DEFAULT);
        assertEquals("everyupdate", NotificationLevel.EVERY_UPDATE.key(), "the lang key suffix");
    }

    @Test
    void everyUpdateShowsEveryMarkedToast() {
        assertTrue(NotificationLevel.EVERY_UPDATE.allows(TICK, false), "a tick between marks");
        assertTrue(NotificationLevel.EVERY_UPDATE.allows(TICK, null), "a tick with no mark authored");
        assertTrue(NotificationLevel.EVERY_UPDATE.allows(FINISH, true));
        assertTrue(NotificationLevel.EVERY_UPDATE.allows(DONE, null));
    }

    @Test
    void milestonesShowsATickOnlyAtAnAuthoredMarkOrAFinish() {
        assertFalse(NotificationLevel.MILESTONES.allows(TICK, false));
        assertFalse(NotificationLevel.MILESTONES.allows(TICK, null), "no mark authored: only the finish counts");
        assertTrue(NotificationLevel.MILESTONES.allows(TICK, true));
        assertTrue(NotificationLevel.MILESTONES.allows(FINISH, true));
        assertTrue(NotificationLevel.MILESTONES.allows(DONE, null), "a completion, a claim or an unlock is no tick");
    }

    @Test
    void finishesShowsATickOnlyAtAFinish() {
        assertFalse(NotificationLevel.FINISHES.allows(TICK, true), "a mark is not a finish");
        assertFalse(NotificationLevel.FINISHES.allows(TICK, false));
        assertTrue(NotificationLevel.FINISHES.allows(FINISH, true));
        assertTrue(NotificationLevel.FINISHES.allows(DONE, null));
    }

    @Test
    void noneShowsNoMarkedToastAtAll() {
        assertFalse(NotificationLevel.NONE.allows(TICK, true));
        assertFalse(NotificationLevel.NONE.allows(FINISH, true));
        assertFalse(NotificationLevel.NONE.allows(DONE, null));
    }
}
