package com.ziggfreed.common.objectives.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.quest.event.QuestAbandonedEvent;
import com.ziggfreed.common.quest.event.QuestAcceptedEvent;
import com.ziggfreed.common.quest.event.QuestClaimedEvent;
import com.ziggfreed.common.quest.event.QuestCompletedEvent;
import com.ziggfreed.common.quest.event.QuestObjectiveProgressedEvent;
import com.ziggfreed.common.quest.event.QuestTrackedEvent;

/**
 * Each of the six quest events reaches exactly the tracker of the player it names, and no other;
 * the objective event - the one that fires on ordinary play - is skipped for a quest the tracker
 * is not showing; and the registry the events look in is keyed by uuid, written at attach and
 * cleared at detach, so a player who left is never repainted.
 *
 * <p>Driven through the same static handlers the event bus calls, over a recording tracker, since a
 * real one needs a live player and there is no event bus in a unit JVM.
 */
class TrackedQuestHudEventTest {

    /** A tracker that counts repaints and shows a fixed set of quests. */
    private static final class Recording implements TrackedQuestHuds.Tracker {

        final AtomicInteger repaints = new AtomicInteger();
        final Set<String> showing;

        Recording(@Nonnull Set<String> showing) {
            this.showing = showing;
        }

        @Override
        public void repaint() {
            repaints.incrementAndGet();
        }

        @Override
        public boolean shows(@Nonnull String questId) {
            return showing.contains(questId);
        }
    }

    private final UUID watcher = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();
    private Recording watcherHud;
    private Recording strangerHud;

    @BeforeEach
    void attachTwoPlayers() {
        watcherHud = new Recording(Set.of("q_pinned"));
        strangerHud = new Recording(Set.of("q_pinned"));
        TrackedQuestHuds.register(watcher, watcherHud);
        TrackedQuestHuds.register(stranger, strangerHud);
    }

    @AfterEach
    void detachEverybody() {
        TrackedQuestHuds.unregister(watcher);
        TrackedQuestHuds.unregister(stranger);
    }

    // ==================== one repaint per event, for the named player only ====================

    @Test
    void aPinChangeRepaintsThatPlayerOnce() {
        TrackedQuestHuds.onTracked(new QuestTrackedEvent("q_any", watcher, true, List.of()));
        assertEquals(1, watcherHud.repaints.get());
        assertEquals(0, strangerHud.repaints.get());
    }

    @Test
    void anAcceptRepaintsThatPlayerOnce() {
        TrackedQuestHuds.onAccepted(new QuestAcceptedEvent("q_any", watcher, List.of()));
        assertEquals(1, watcherHud.repaints.get());
        assertEquals(0, strangerHud.repaints.get());
    }

    @Test
    void anObjectiveMovingOnAShownQuestRepaintsThatPlayerOnce() {
        TrackedQuestHuds.onObjectiveProgressed(
                new QuestObjectiveProgressedEvent("q_pinned", "logs", watcher, 1, 3, false, List.of()));
        assertEquals(1, watcherHud.repaints.get());
        assertEquals(0, strangerHud.repaints.get());
    }

    @Test
    void anObjectiveMovingOnAQuestNotShownCostsNoPaint() {
        TrackedQuestHuds.onObjectiveProgressed(
                new QuestObjectiveProgressedEvent("q_elsewhere", "logs", watcher, 1, 3, false, List.of()));
        assertEquals(0, watcherHud.repaints.get(), "the tracker is not showing that quest");
    }

    @Test
    void aCompletionRepaintsThatPlayerOnce() {
        TrackedQuestHuds.onCompleted(new QuestCompletedEvent("q_any", watcher, false, List.of()));
        assertEquals(1, watcherHud.repaints.get());
        assertEquals(0, strangerHud.repaints.get());
    }

    @Test
    void aClaimRepaintsThatPlayerOnce() {
        TrackedQuestHuds.onClaimed(new QuestClaimedEvent("q_any", watcher, 1, 0, 0, List.of()));
        assertEquals(1, watcherHud.repaints.get());
        assertEquals(0, strangerHud.repaints.get());
    }

    @Test
    void anAbandonRepaintsThatPlayerOnce() {
        TrackedQuestHuds.onAbandoned(new QuestAbandonedEvent("q_any", watcher, List.of()));
        assertEquals(1, watcherHud.repaints.get());
        assertEquals(0, strangerHud.repaints.get());
    }

    // ==================== the registry ====================

    @Test
    void anEventForAPlayerWithNoTrackerIsANoOp() {
        assertFalse(TrackedQuestHuds.repaint(UUID.randomUUID()));
        TrackedQuestHuds.onAccepted(new QuestAcceptedEvent("q_any", UUID.randomUUID(), List.of()));
        assertEquals(0, watcherHud.repaints.get());
        assertEquals(0, strangerHud.repaints.get());
    }

    @Test
    void detachClearsTheEntryAndNothingRepaintsForThatPlayerAfterwards() {
        assertTrue(TrackedQuestHuds.isLive(watcher));
        TrackedQuestHuds.unregister(watcher);
        assertFalse(TrackedQuestHuds.isLive(watcher));

        assertFalse(TrackedQuestHuds.repaint(watcher));
        TrackedQuestHuds.onTracked(new QuestTrackedEvent("q_any", watcher, false, List.of()));
        assertEquals(0, watcherHud.repaints.get(), "a player who left is never repainted");
    }

    @Test
    void aReconnectReplacesTheStaleTracker() {
        Recording fresh = new Recording(Set.of());
        TrackedQuestHuds.register(watcher, fresh);
        assertTrue(TrackedQuestHuds.repaint(watcher));
        assertEquals(1, fresh.repaints.get());
        assertEquals(0, watcherHud.repaints.get(), "the stale one from the old session is gone");
    }

    @Test
    void repaintAllOnlineReachesEveryLiveTrackerOnce() {
        TrackedQuestHuds.repaintAllOnline();
        assertEquals(1, watcherHud.repaints.get());
        assertEquals(1, strangerHud.repaints.get());
    }
}
