package com.ziggfreed.common.world;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.ziggfreed.common.world.TickingSections.SectionPos;
import com.ziggfreed.common.world.TickingSections.State;

/**
 * Whether an entity added at a block stays in the world, driven through the package-private section
 * seam, since a unit JVM holds no chunk store: which section a block is filed under, that a step never
 * runs while its section sleeps (X29, the hub NPC parked on a headless Update 7 boot), that it runs on
 * the world thread once a wake lands, and what a failed, refused or throwing wake, or a world that takes
 * no more tasks, answers. The engine reads behind the seam are proved by the leg's linkage check and the
 * coordinator's boot.
 */
class TickingSectionsTest {

    private static final SectionPos HUB = new SectionPos(2, 3, 9);
    private static final SectionPos ELSEWHERE = new SectionPos(-4, 2, 7);

    /** A chunk store stand-in: a state per section, scripted wakes, counted calls. */
    private static final class FakeSections implements TickingSections.Sections {
        private final Map<SectionPos, State> states = new HashMap<>();
        private final Map<SectionPos, CompletableFuture<Ref<ChunkStore>>> loading = new HashMap<>();
        /** A section in memory ticks before the request returns, as on the world thread. */
        private boolean wakesWithinTheCall = true;
        private RuntimeException wakeThrows;
        private int wakes;
        private int holds;

        FakeSections with(SectionPos section, State state) {
            states.put(section, state);
            return this;
        }

        @Override
        public State stateOf(SectionPos section) {
            return states.getOrDefault(section, State.ABSENT);
        }

        @Override
        public CompletableFuture<Ref<ChunkStore>> wake(SectionPos section) {
            wakes++;
            if (wakeThrows != null) {
                throw wakeThrows;
            }
            if (states.get(section) == State.PARKING && wakesWithinTheCall) {
                states.put(section, State.TICKING);
                return CompletableFuture.completedFuture(null);
            }
            return loading.computeIfAbsent(section, s -> new CompletableFuture<>());
        }

        @Override
        public boolean hold(SectionPos section) {
            holds++;
            return true;
        }

        /** The chunk store lands the request: the section is in memory and ticking. */
        void land(SectionPos section) {
            states.put(section, State.TICKING);
            loading.remove(section).complete(null);
        }

        /** The request lands, but the section went back to sleep before the step could run. */
        void landAsleep(SectionPos section) {
            states.put(section, State.PARKING);
            loading.remove(section).complete(null);
        }

        void fail(SectionPos section, Throwable cause) {
            loading.remove(section).completeExceptionally(cause);
        }
    }

    /** A world thread held still: a task waits here until the test runs it. */
    private static final class HeldTasks implements Executor {
        private final Deque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        int pending() {
            return tasks.size();
        }

        int runAll() {
            int ran = 0;
            for (Runnable task = tasks.poll(); task != null; task = tasks.poll()) {
                task.run();
                ran++;
            }
            return ran;
        }
    }

    @Test
    void aBlockIsFiledUnderTheSectionTheEngineResolves_negativeCoordinatesFloor() {
        assertEquals(new SectionPos(2, 3, 9), SectionPos.ofBlock(68, 117, 299));
        assertEquals(new SectionPos(0, 0, 0), SectionPos.ofBlock(31.999, 0, 0));
        assertEquals(new SectionPos(-1, -1, -1), SectionPos.ofBlock(-0.5, -32, -1));
        assertEquals(new SectionPos(-2, 1, 1), SectionPos.ofBlock(-33, 32, 63.9));
    }

    @Test
    void aTickingSectionRunsTheStepAtOnceAndWakesNothing() {
        FakeSections sections = new FakeSections().with(HUB, State.TICKING);
        HeldTasks world = new HeldTasks();
        List<String> refused = new ArrayList<>();
        int[] ran = {0};

        TickingSections.whenTicking(sections, world, HUB, () -> ran[0]++, refused::add);

        assertEquals(1, ran[0], "ran during the call");
        assertEquals(0, sections.wakes);
        assertEquals(0, world.pending());
        assertTrue(refused.isEmpty());
    }

    @Test
    void parkedSpawnRegression_aSleepingSectionInMemoryIsWokenBeforeTheStepRuns() {
        // X29: on Update 7 a section loads asleep whatever its column does, and an NPC added into it is
        // parked on the spot with its post-spawn never run. The step must never see a sleeping section.
        FakeSections sections = new FakeSections().with(HUB, State.PARKING);
        HeldTasks world = new HeldTasks();
        List<State> seenByTheStep = new ArrayList<>();
        List<String> refused = new ArrayList<>();

        TickingSections.whenTicking(sections, world, HUB, () -> seenByTheStep.add(sections.stateOf(HUB)),
                refused::add);

        assertEquals(List.of(State.TICKING), seenByTheStep, "woken on the world thread, then run during the call");
        assertEquals(1, sections.wakes);
        assertEquals(0, world.pending());
        assertEquals(List.of(), refused);
    }

    @Test
    void aSectionNotInMemoryRunsTheStepOnTheWorldThreadOnceItsWakeLands() {
        FakeSections sections = new FakeSections();
        HeldTasks world = new HeldTasks();
        List<State> seenByTheStep = new ArrayList<>();
        List<String> refused = new ArrayList<>();

        TickingSections.whenTicking(sections, world, HUB, () -> seenByTheStep.add(sections.stateOf(HUB)),
                refused::add);
        assertTrue(seenByTheStep.isEmpty(), "nothing runs while the section loads");
        sections.land(HUB);
        assertTrue(seenByTheStep.isEmpty(), "never on the thread that landed the load");
        assertEquals(1, world.runAll());
        assertEquals(List.of(State.TICKING), seenByTheStep);
        assertEquals(List.of(), refused);
    }

    @Test
    void aWakeThatLandsWithTheSectionStillAsleepRefusesAndNeverRunsTheStep() {
        FakeSections sections = new FakeSections().with(HUB, State.PARKING);
        sections.wakesWithinTheCall = false;
        HeldTasks world = new HeldTasks();
        List<String> refused = new ArrayList<>();
        int[] ran = {0};

        TickingSections.whenTicking(sections, world, HUB, () -> ran[0]++, refused::add);
        sections.landAsleep(HUB);
        world.runAll();

        assertEquals(0, ran[0], "a step never runs into a sleeping section");
        assertEquals(1, refused.size());
        assertTrue(refused.get(0).contains("still not ticking"), refused.get(0));
    }

    @Test
    void aFailedOrThrowingWakeRefusesAndNeverThrows() {
        HeldTasks world = new HeldTasks();
        int[] ran = {0};

        FakeSections failing = new FakeSections();
        List<String> refused = new ArrayList<>();
        TickingSections.whenTicking(failing, world, HUB, () -> ran[0]++, refused::add);
        failing.fail(HUB, new IllegalStateException("generation failed"));
        world.runAll();
        assertEquals(1, refused.size());
        assertTrue(refused.get(0).contains("generation failed"), refused.get(0));

        FakeSections throwing = new FakeSections();
        throwing.wakeThrows = new IllegalStateException("the store is shutting down");
        List<String> refusedToo = new ArrayList<>();
        assertDoesNotThrow(() -> TickingSections.whenTicking(throwing, world, HUB, () -> ran[0]++, refusedToo::add));
        world.runAll();
        assertEquals(1, refusedToo.size());
        assertEquals(0, ran[0]);
    }

    @Test
    void aStepThatThrowsNeverReachesTheCallerOrTheLoader() {
        HeldTasks world = new HeldTasks();
        Runnable broken = () -> {
            throw new IllegalStateException("the role is not registered");
        };

        List<String> refused = new ArrayList<>();

        FakeSections ticking = new FakeSections().with(HUB, State.TICKING);
        assertDoesNotThrow(() -> TickingSections.whenTicking(ticking, world, HUB, broken, refused::add));

        FakeSections absent = new FakeSections();
        TickingSections.whenTicking(absent, world, HUB, broken, refused::add);
        assertDoesNotThrow(() -> absent.land(HUB));
        assertDoesNotThrow(world::runAll, "the step's own throw stays inside its continuation");
        assertEquals(List.of(), refused, "a step that throws is logged, never refused");
    }

    @Test
    void aWorldThatTakesNoMoreTasksRefusesOnceAndNeverRunsTheStep() {
        // A stopping world's execute throws instead of queueing the continuation, so the continuation never
        // runs: only the rejection itself can tell the caller.
        Executor stopping = task -> {
            throw new IllegalStateException("World thread is not accepting tasks");
        };
        int[] ran = {0};

        FakeSections landing = new FakeSections();
        List<String> refused = new ArrayList<>();
        TickingSections.whenTicking(landing, stopping, HUB, () -> ran[0]++, refused::add);
        assertTrue(refused.isEmpty(), "nothing is refused while the section loads");
        assertDoesNotThrow(() -> landing.land(HUB), "the rejection never reaches the loading thread");
        assertEquals(1, refused.size(), "refused on the thread that landed the wake");
        assertTrue(refused.get(0).contains("not accepting tasks"), refused.get(0));

        FakeSections failing = new FakeSections();
        List<String> refusedOnce = new ArrayList<>();
        TickingSections.whenTicking(failing, stopping, HUB, () -> ran[0]++, refusedOnce::add);
        assertDoesNotThrow(() -> failing.fail(HUB, new IllegalStateException("generation failed")));
        assertEquals(1, refusedOnce.size(), "a failed wake the world takes no task for is refused once, not twice");

        FakeSections throwing = new FakeSections();
        throwing.wakeThrows = new IllegalStateException("the store is shutting down");
        List<String> refusedAtOnce = new ArrayList<>();
        assertDoesNotThrow(() -> TickingSections.whenTicking(throwing, stopping, HUB, () -> ran[0]++,
                refusedAtOnce::add));
        assertEquals(1, refusedAtOnce.size(), "a wake already over is refused during the call");

        assertEquals(0, ran[0], "the step never runs");
    }

    @Test
    void ensureTickingWakesOnlyASleepingSectionInMemoryAndNeverLoads() {
        FakeSections sections = new FakeSections().with(HUB, State.PARKING);
        assertTrue(TickingSections.ensureTicking(sections, HUB));
        assertEquals(1, sections.wakes);
        assertTrue(TickingSections.ensureTicking(sections, HUB), "ticking now: nothing to wake");
        assertEquals(1, sections.wakes);

        FakeSections absent = new FakeSections();
        assertFalse(TickingSections.ensureTicking(absent, HUB), "not in memory: an add there cannot stay");
        assertEquals(0, absent.wakes, "and it starts no load");

        FakeSections offThread = new FakeSections().with(HUB, State.PARKING);
        offThread.wakesWithinTheCall = false;
        assertFalse(TickingSections.ensureTicking(offThread, HUB),
                "a wake that has not landed is not a ticking section");
    }

    @Test
    void aReadThatThrowsReadsAsNotInMemory() {
        TickingSections.Sections broken = new TickingSections.Sections() {
            @Override
            public State stateOf(SectionPos section) {
                throw new IllegalStateException("the chunk store is gone");
            }

            @Override
            public CompletableFuture<Ref<ChunkStore>> wake(SectionPos section) {
                return new CompletableFuture<>();
            }

            @Override
            public boolean hold(SectionPos section) {
                return true;
            }
        };

        assertEquals(State.ABSENT, TickingSections.stateOf(broken, HUB));
        assertFalse(TickingSections.ensureTicking(broken, HUB));
        assertFalse(TickingSections.holdTicking(broken, HUB));
    }

    @Test
    void onlyATickingSectionIsHeld_andAHoldNeverWakes() {
        FakeSections sections = new FakeSections().with(HUB, State.TICKING).with(ELSEWHERE, State.PARKING);

        assertTrue(TickingSections.holdTicking(sections, HUB));
        assertFalse(TickingSections.holdTicking(sections, ELSEWHERE),
                "holding a sleeping section would not bring its entities back");
        assertFalse(TickingSections.holdTicking(sections, new SectionPos(0, 0, 0)));
        assertEquals(1, sections.holds);
        assertEquals(0, sections.wakes, "a hold never wakes: an entity-store system calls it");
    }
}
