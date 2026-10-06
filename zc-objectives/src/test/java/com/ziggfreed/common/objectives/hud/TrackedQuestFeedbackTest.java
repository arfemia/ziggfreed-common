package com.ziggfreed.common.objectives.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.feedback.moment.FeedbackEngine;
import com.ziggfreed.common.progress.runtime.ProgressionFeedbackHook;
import com.ziggfreed.common.subject.Subject;

/**
 * Every quest moment about a quest the tracker is drawing for that player is marked on screen, so the
 * engine draws none of its toasts in the corner: a step, the tick that finishes one, the completion, the
 * park and the collect. A quest off the panel, a hidden panel (the player's Show off, the owner's switch off and the
 * consumer's audience saying no all paint it hidden), a player with no tracker, an achievement and a
 * moment naming no quest pass through as they came.
 */
class TrackedQuestFeedbackTest {

    private static final List<String> QUEST_MOMENTS = List.of("Quest_Objective_Progressed", "Quest_Completed",
            "Quest_Parked", "Quest_Claimed");

    /** A tracker painting one quest, on a panel the test raises and lowers. */
    private static final class Panel implements TrackedQuestHuds.Tracker {

        boolean visible = true;
        final Set<String> painted = Set.of("q_pinned");

        @Override
        public void repaint() {
        }

        @Override
        public boolean shows(@Nonnull String questId) {
            return painted.contains(questId);
        }

        @Override
        public boolean drawing(@Nonnull String questId) {
            return visible && painted.contains(questId);
        }
    }

    private final UUID watcher = UUID.randomUUID();
    private final List<Map<String, Object>> fired = new ArrayList<>();
    private Panel panel;
    private ProgressionFeedbackHook hook;

    @BeforeEach
    void attach() {
        panel = new Panel();
        TrackedQuestHuds.register(watcher, panel);
        hook = TrackedQuestFeedback.marking((momentId, subject, args) -> fired.add(args));
    }

    @AfterEach
    void detach() {
        TrackedQuestHuds.unregister(watcher);
    }

    @Nonnull
    private Map<String, Object> fire(@Nonnull String momentId, @Nonnull UUID player,
            @Nonnull Map<String, Object> args) {
        fired.clear();
        hook.fire(momentId, Subject.of(player, "tester"), args);
        assertEquals(1, fired.size(), "the moment reaches the engine once, marked or not");
        return fired.get(0);
    }

    @Nonnull
    private static Map<String, Object> about(@Nonnull String questId) {
        return Map.of("quest", questId, "title", "x");
    }

    private static boolean onScreen(@Nonnull Map<String, Object> args) {
        return Boolean.TRUE.equals(args.get(FeedbackEngine.ON_SCREEN_ARG));
    }

    @Test
    void everyQuestMomentAboutAQuestOnTheTrackerIsMarkedOnScreen() {
        for (String momentId : QUEST_MOMENTS) {
            assertTrue(onScreen(fire(momentId, watcher, about("q_pinned"))), momentId);
        }
    }

    /** The finish is no exception any more: the panel shows the step done, so no toast repeats it. */
    @Test
    void theTickThatFinishesAStepIsMarkedToo() {
        assertTrue(onScreen(fire("Quest_Objective_Progressed", watcher,
                Map.of("quest", "q_pinned", "current", 10, "required", 10, FeedbackEngine.FINISHED_ARG, true))));
    }

    @Test
    void aQuestThePanelIsNotDrawingIsNotMarked() {
        for (String momentId : QUEST_MOMENTS) {
            assertFalse(onScreen(fire(momentId, watcher, about("q_other"))), momentId);
        }
    }

    @Test
    void aHiddenPanelMarksNothing() {
        panel.visible = false;

        for (String momentId : QUEST_MOMENTS) {
            assertFalse(onScreen(fire(momentId, watcher, about("q_pinned"))), momentId);
        }
    }

    @Test
    void aPlayerWithNoTrackerIsNeverMarked() {
        assertFalse(onScreen(fire("Quest_Completed", UUID.randomUUID(), about("q_pinned"))));
    }

    /** Achievements keep their own rule, even one whose authored values name a quest on the panel. */
    @Test
    void anAchievementIsNeverMarked() {
        for (String momentId : List.of("Achievement_Unlocked", "Achievement_Claimed")) {
            assertFalse(onScreen(fire(momentId, watcher, about("q_pinned"))), momentId);
        }
    }

    /** Nothing to mark hands the engine the very map the quest engine built, untouched. */
    @Test
    void aMomentNamingNoQuestPassesThroughAsItCame() {
        Map<String, Object> args = Map.of("title", "x");

        assertSame(args, fire("Quest_Completed", watcher, args));
    }

    /** The marked copy leaves the quest engine's own map alone (it may be read-only) and keeps every value. */
    @Test
    void markingCopiesAndKeepsEveryValue() {
        Map<String, Object> args = about("q_pinned");

        Map<String, Object> marked = fire("Quest_Claimed", watcher, args);

        assertFalse(args.containsKey(FeedbackEngine.ON_SCREEN_ARG), "the original is untouched");
        assertEquals("q_pinned", marked.get("quest"));
        assertEquals("x", marked.get("title"));
    }

    /** The decorated hook still listens and answers exactly as the one it wraps, so an unauthored moment costs nothing. */
    @Test
    void theHookListensAndAnswersAsTheOneItWraps() {
        ProgressionFeedbackHook quiet = TrackedQuestFeedback.marking(ProgressionFeedbackHook.NONE);
        assertFalse(quiet.listening());
        assertFalse(quiet.answers("Quest_Completed"));

        ProgressionFeedbackHook picky = TrackedQuestFeedback.marking(
                ProgressionFeedbackHook.of((momentId, subject, args) -> { }, "Quest_Completed"::equals));
        assertTrue(picky.answers("Quest_Completed"));
        assertFalse(picky.answers("Quest_Parked"));
    }
}
