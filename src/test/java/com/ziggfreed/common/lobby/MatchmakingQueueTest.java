package com.ziggfreed.common.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

/**
 * Deterministic unit tests for the {@link MatchmakingQueue} state machine, driven by a
 * manual {@link DelayScheduler} (no wall-clock waiting). Player-facing delivery
 * (Notify/EventTitles/Universe) is a guarded no-op with no server, so these exercise
 * pure UUID/state logic. The launch is observed through a recording {@link RoundLauncher}.
 */
class MatchmakingQueueTest {

    // ==================== test doubles ====================

    /** Records scheduled tasks; the test fires them by hand to advance time. */
    private static final class ManualScheduler implements DelayScheduler {
        private final Deque<Task> tasks = new ArrayDeque<>();

        private static final class Task implements Cancellable {
            final Runnable run;
            boolean cancelled;
            Task(Runnable run) { this.run = run; }
            @Override public void cancel() { cancelled = true; }
        }

        @Override
        public Cancellable schedule(Runnable task, long delay, TimeUnit unit) {
            Task t = new Task(task);
            tasks.add(t);
            return t;
        }

        /** Fire the next not-cancelled task (FIFO). Returns false if none remained. */
        boolean runNext() {
            while (!tasks.isEmpty()) {
                Task t = tasks.poll();
                if (!t.cancelled) {
                    t.run.run();
                    return true;
                }
            }
            return false;
        }

        /** Fire tasks until the queue of pending tasks drains (countdown self-reschedules until launch). */
        void runAll() {
            int guard = 0;
            while (runNext()) {
                if (++guard > 10_000) {
                    throw new IllegalStateException("scheduler did not converge");
                }
            }
        }
    }

    private static final class RecordingLauncher implements RoundLauncher {
        int calls;
        UUID initiator;
        List<UUID> party = new ArrayList<>();
        final CompletableFuture<Object> future = new CompletableFuture<>();

        @Override
        public CompletableFuture<?> launch(UUID initiator, List<UUID> party) {
            this.calls++;
            this.initiator = initiator;
            this.party = new ArrayList<>(party);
            return future;
        }
    }

    private static Predicate<UUID> inSet(Set<UUID> set) {
        return set::contains;
    }

    private static final QueueMessages NO_MESSAGES = new QueueMessages() { };

    private static UUID id() {
        return UUID.randomUUID();
    }

    // ==================== tests ====================

    @Test
    void joinBelowMinStaysOpenAndDoesNotLaunch() {
        ManualScheduler sched = new ManualScheduler();
        LobbyService svc = new LobbyService(sched);
        RecordingLauncher launcher = new RecordingLauncher();
        MatchmakingQueue q = svc.queue(new QueueKey("g", "p"),
                new LobbyConfig(2, 4, 5, 3, false, false),
                launcher, u -> false, u -> true, NO_MESSAGES);

        assertTrue(q.join(id()).ok());
        assertEquals(QueueState.OPEN, q.state());
        sched.runAll();
        assertEquals(0, launcher.calls, "a lone player below min must not launch");
        assertEquals(QueueState.OPEN, q.state());
    }

    @Test
    void minReachedRunsFillWindowThenCountdownThenLaunches() {
        ManualScheduler sched = new ManualScheduler();
        LobbyService svc = new LobbyService(sched);
        RecordingLauncher launcher = new RecordingLauncher();
        MatchmakingQueue q = svc.queue(new QueueKey("g", "p"),
                new LobbyConfig(2, 4, 5, 3, false, false),
                launcher, u -> false, u -> true, NO_MESSAGES);

        UUID a = id();
        UUID b = id();
        assertTrue(q.join(a).ok());
        assertTrue(q.join(b).ok()); // hits min=2 -> fill window scheduled
        assertEquals(QueueState.OPEN, q.state());

        sched.runNext(); // fill window elapses -> COUNTDOWN
        assertEquals(QueueState.COUNTDOWN, q.state());

        sched.runAll(); // drain the 3 countdown ticks -> launch
        assertEquals(1, launcher.calls);
        assertEquals(a, launcher.initiator, "first joiner is the initiator");
        assertEquals(List.of(a, b), launcher.party);
    }

    @Test
    void maxPartyStartsCountdownImmediatelySkippingFillWindow() {
        ManualScheduler sched = new ManualScheduler();
        LobbyService svc = new LobbyService(sched);
        RecordingLauncher launcher = new RecordingLauncher();
        MatchmakingQueue q = svc.queue(new QueueKey("g", "p"),
                new LobbyConfig(2, 2, 30, 0, false, false), // max=2, countdown=0 -> instant launch
                launcher, u -> false, u -> true, NO_MESSAGES);

        q.join(id());
        q.join(id()); // hits max=2 -> COUNTDOWN now (countdown=0 -> schedules immediate launch)
        assertEquals(QueueState.COUNTDOWN, q.state());
        sched.runAll();
        assertEquals(1, launcher.calls);
    }

    @Test
    void allowSoloLaunchesLonePlayerAtFillTimeout() {
        ManualScheduler sched = new ManualScheduler();
        LobbyService svc = new LobbyService(sched);
        RecordingLauncher launcher = new RecordingLauncher();
        MatchmakingQueue q = svc.queue(new QueueKey("g", "p"),
                new LobbyConfig(2, 4, 5, 0, true, false), // min=2 but allowSolo -> 1 triggers + launches
                launcher, u -> false, u -> true, NO_MESSAGES);

        UUID a = id();
        q.join(a); // allowSolo -> fill window starts on first join
        sched.runAll(); // fill elapses -> countdown(0) -> launch solo
        assertEquals(1, launcher.calls);
        assertEquals(a, launcher.initiator);
        assertEquals(List.of(a), launcher.party);
    }

    @Test
    void leaveBelowFloorDuringFillWindowCancels() {
        ManualScheduler sched = new ManualScheduler();
        LobbyService svc = new LobbyService(sched);
        RecordingLauncher launcher = new RecordingLauncher();
        MatchmakingQueue q = svc.queue(new QueueKey("g", "p"),
                new LobbyConfig(2, 4, 5, 3, false, false),
                launcher, u -> false, u -> true, NO_MESSAGES);

        UUID a = id();
        UUID b = id();
        q.join(a);
        q.join(b); // fill window armed
        assertTrue(q.leave(b)); // back below min -> cancel
        sched.runAll();
        assertEquals(0, launcher.calls);
        assertEquals(QueueState.OPEN, q.state());
        assertEquals(1, q.size());
    }

    @Test
    void leaveDuringCountdownReopens() {
        ManualScheduler sched = new ManualScheduler();
        LobbyService svc = new LobbyService(sched);
        RecordingLauncher launcher = new RecordingLauncher();
        MatchmakingQueue q = svc.queue(new QueueKey("g", "p"),
                new LobbyConfig(2, 4, 5, 3, false, false),
                launcher, u -> false, u -> true, NO_MESSAGES);

        UUID a = id();
        UUID b = id();
        q.join(a);
        q.join(b);
        sched.runNext(); // fill -> COUNTDOWN
        assertEquals(QueueState.COUNTDOWN, q.state());
        q.leave(a); // drops to 1 < min -> cancel countdown, reopen
        assertEquals(QueueState.OPEN, q.state());
        sched.runAll();
        assertEquals(0, launcher.calls);
    }

    @Test
    void doubleJoinSameQueueRejected() {
        ManualScheduler sched = new ManualScheduler();
        LobbyService svc = new LobbyService(sched);
        MatchmakingQueue q = svc.queue(new QueueKey("g", "p"),
                new LobbyConfig(2, 4, 5, 3, false, false),
                new RecordingLauncher(), u -> false, u -> true, NO_MESSAGES);

        UUID a = id();
        assertEquals(JoinResult.JOINED, q.join(a));
        assertEquals(JoinResult.ALREADY_QUEUED, q.join(a));
        assertEquals(1, q.size());
    }

    @Test
    void cannotJoinTwoQueuesAtOnce() {
        ManualScheduler sched = new ManualScheduler();
        LobbyService svc = new LobbyService(sched);
        MatchmakingQueue q1 = svc.queue(new QueueKey("g", "p1"),
                new LobbyConfig(2, 4, 5, 3, false, false),
                new RecordingLauncher(), u -> false, u -> true, NO_MESSAGES);
        MatchmakingQueue q2 = svc.queue(new QueueKey("g", "p2"),
                new LobbyConfig(2, 4, 5, 3, false, false),
                new RecordingLauncher(), u -> false, u -> true, NO_MESSAGES);

        UUID a = id();
        assertEquals(JoinResult.JOINED, q1.join(a));
        assertEquals(JoinResult.ALREADY_QUEUED, q2.join(a));
        assertTrue(svc.isQueued(a));
        assertEquals(new QueueKey("g", "p1"), svc.queueKeyOf(a));
    }

    @Test
    void alreadyEngagedRejectedAtJoin() {
        ManualScheduler sched = new ManualScheduler();
        LobbyService svc = new LobbyService(sched);
        Set<UUID> engaged = new HashSet<>();
        UUID a = id();
        engaged.add(a);
        MatchmakingQueue q = svc.queue(new QueueKey("g", "p"),
                new LobbyConfig(1, 4, 5, 3, false, false),
                new RecordingLauncher(), inSet(engaged), u -> true, NO_MESSAGES);

        assertEquals(JoinResult.ALREADY_ENGAGED, q.join(a));
        assertFalse(svc.isQueued(a));
    }

    @Test
    void launchFiltersOfflineAndEngagedAndRepicksInitiator() {
        ManualScheduler sched = new ManualScheduler();
        LobbyService svc = new LobbyService(sched);
        RecordingLauncher launcher = new RecordingLauncher();
        UUID a = id();
        UUID b = id();
        UUID c = id();
        Set<UUID> online = new HashSet<>(Set.of(b, c)); // a went offline before launch
        Set<UUID> engaged = new HashSet<>();            // empty at join time
        MatchmakingQueue q = svc.queue(new QueueKey("g", "p"),
                new LobbyConfig(3, 3, 30, 0, false, false), // max=3 -> instant countdown, countdown=0
                launcher, inSet(engaged), inSet(online), NO_MESSAGES);

        q.join(a);
        q.join(b);
        q.join(c); // all three join (none engaged yet) -> hits max
        engaged.add(c); // c entered a round another way between snapshot trigger and launch
        sched.runAll();

        assertEquals(1, launcher.calls);
        assertEquals(List.of(b), launcher.party, "offline a + engaged c are filtered out");
        assertEquals(b, launcher.initiator, "initiator re-picked to first still-eligible joiner");
    }

    @Test
    void launchAbortsWhenNobodyEligible() {
        ManualScheduler sched = new ManualScheduler();
        LobbyService svc = new LobbyService(sched);
        RecordingLauncher launcher = new RecordingLauncher();
        UUID a = id();
        UUID b = id();
        MatchmakingQueue q = svc.queue(new QueueKey("g", "p"),
                new LobbyConfig(2, 2, 30, 0, false, false),
                launcher, u -> false, u -> false /* all offline */, NO_MESSAGES);

        q.join(a);
        q.join(b);
        sched.runAll();
        assertEquals(0, launcher.calls, "no eligible players -> launcher never fires");
        assertEquals(QueueState.CLOSED, q.state());
    }

    @Test
    void queueIsFreshAfterLaunchCloses() {
        ManualScheduler sched = new ManualScheduler();
        LobbyService svc = new LobbyService(sched);
        RecordingLauncher launcher = new RecordingLauncher();
        QueueKey key = new QueueKey("g", "p");
        MatchmakingQueue q1 = svc.queue(key, new LobbyConfig(1, 1, 30, 0, false, false),
                launcher, u -> false, u -> true, NO_MESSAGES);
        q1.join(id()); // min=max=1 -> instant launch
        sched.runAll();
        assertEquals(1, launcher.calls);
        assertEquals(QueueState.CLOSED, q1.state());

        // A get-or-create after close hands back a fresh OPEN queue, not the closed one.
        MatchmakingQueue q2 = svc.queue(key, new LobbyConfig(1, 1, 30, 0, false, false),
                launcher, u -> false, u -> true, NO_MESSAGES);
        assertEquals(QueueState.OPEN, q2.state());
        assertTrue(q1 != q2);
    }

    @Test
    void leaderForceStartSkipsFillWindow() {
        ManualScheduler sched = new ManualScheduler();
        LobbyService svc = new LobbyService(sched);
        RecordingLauncher launcher = new RecordingLauncher();
        MatchmakingQueue q = svc.queue(new QueueKey("g", "p"),
                new LobbyConfig(1, 4, 60, 0, true, true),
                launcher, u -> false, u -> true, NO_MESSAGES);

        UUID a = id();
        UUID b = id();
        q.join(a);
        q.join(b);
        assertTrue(q.forceStart(a), "the initiator may force start");
        sched.runAll();
        assertEquals(1, launcher.calls);
        assertEquals(a, launcher.initiator);
        assertEquals(List.of(a, b), launcher.party);
    }

    @Test
    void nonInitiatorCannotForceStart() {
        ManualScheduler sched = new ManualScheduler();
        LobbyService svc = new LobbyService(sched);
        RecordingLauncher launcher = new RecordingLauncher();
        MatchmakingQueue q = svc.queue(new QueueKey("g", "p"),
                new LobbyConfig(1, 4, 60, 5, true, true),
                launcher, u -> false, u -> true, NO_MESSAGES);

        UUID a = id();
        UUID b = id();
        q.join(a);
        q.join(b);
        assertFalse(q.forceStart(b), "only the first joiner is the leader");
    }

    @Test
    void serviceLeaveRemovesFromQueue() {
        ManualScheduler sched = new ManualScheduler();
        LobbyService svc = new LobbyService(sched);
        MatchmakingQueue q = svc.queue(new QueueKey("g", "p"),
                new LobbyConfig(2, 4, 30, 3, false, false),
                new RecordingLauncher(), u -> false, u -> true, NO_MESSAGES);

        UUID a = id();
        q.join(a);
        assertTrue(svc.isQueued(a));
        assertTrue(svc.leave(a));
        assertFalse(svc.isQueued(a));
        assertFalse(svc.leave(a), "leaving when not queued returns false");
    }

    @Test
    void queueKeyNormalizesCasingAndScope() {
        assertEquals(new QueueKey("Kweebec", "Nightmare"), new QueueKey("kweebec", "nightmare"));
        assertEquals(new QueueKey("g", "p", "World-1"), new QueueKey("g", "p", "world-1"));
        // The convenience (un-scoped) ctor equals an explicit empty scope.
        assertEquals(new QueueKey("g", "p"), new QueueKey("g", "p", ""));
    }
}
