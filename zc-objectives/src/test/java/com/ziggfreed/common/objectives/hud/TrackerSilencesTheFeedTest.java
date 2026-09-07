package com.ziggfreed.common.objectives.hud;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.feedback.moment.FeedbackEngine;

/**
 * When the panel already spells a step out, the corner feed says nothing - and the exceptions to
 * that, each of which is the whole reason the rule is written down rather than assumed: the tick
 * that FINISHES a step still announces, and a player who cannot see the panel keeps every notice.
 */
class TrackerSilencesTheFeedTest {

    private static final String MOMENT = "Quest_Objective_Progressed";

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
    private Panel panel;

    @BeforeEach
    void attach() {
        panel = new Panel();
        TrackedQuestHuds.register(watcher, panel);
    }

    @AfterEach
    void detach() {
        TrackedQuestHuds.unregister(watcher);
    }

    @Nonnull
    private static Map<String, Object> tick(@Nonnull String questId, boolean finished) {
        return Map.of("quest", questId, "current", 6, "required", 10,
                FeedbackEngine.FINISHED_ARG, finished);
    }

    @Test
    void anOrdinaryTickOnAPaintedQuestIsAlreadyOnScreen() {
        assertTrue(TrackedQuestHuds.alreadyShows(watcher, MOMENT, tick("q_pinned", false)));
    }

    /** A finish is a result, not a reading, and all the panel does for it is tick a box. */
    @Test
    void theTickThatFinishesTheStepStillAnnounces() {
        assertFalse(TrackedQuestHuds.alreadyShows(watcher, MOMENT, tick("q_pinned", true)));
    }

    @Test
    void aQuestThePanelIsNotPaintingKeepsItsNotice() {
        assertFalse(TrackedQuestHuds.alreadyShows(watcher, MOMENT, tick("q_other", false)));
    }

    @Test
    void aHiddenPanelSilencesNothing() {
        panel.visible = false;

        assertFalse(TrackedQuestHuds.alreadyShows(watcher, MOMENT, tick("q_pinned", false)));
    }

    @Test
    void aPlayerWithNoTrackerKeepsEveryNotice() {
        assertFalse(TrackedQuestHuds.alreadyShows(
                UUID.randomUUID(), MOMENT, tick("q_pinned", false)));
    }

    /** Only this moment: anything else about a quest on the panel still speaks for itself. */
    @Test
    void anotherMomentAboutThatSameQuestIsUntouched() {
        assertFalse(TrackedQuestHuds.alreadyShows(
                watcher, "Quest_Completed", tick("q_pinned", false)));
    }

    /** A moment naming no quest names nothing the panel could be showing. */
    @Test
    void aMomentWithNoQuestNamedKeepsItsNotice() {
        assertFalse(TrackedQuestHuds.alreadyShows(watcher, MOMENT, Map.of("current", 3)));
    }
}
