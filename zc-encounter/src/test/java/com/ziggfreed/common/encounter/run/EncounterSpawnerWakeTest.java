package com.ziggfreed.common.encounter.run;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.ziggfreed.common.encounter.run.EncounterSpawner.Outcome;
import com.ziggfreed.common.encounter.run.EncounterSpawner.Refusal;

/**
 * What {@code spawnWhenLoaded} answers once its section wake lands, on the package-private seam behind it
 * (a unit JVM holds no world or chunk store): the spawn step's own answer, on the world thread, or, when
 * the world takes no more tasks, {@link Refusal#ENGINE_FAILED} at once, so a caller waiting on the answer
 * (the console spawn command's reply) is never left hanging.
 */
class EncounterSpawnerWakeTest {

    /** What the spawn step answers here, told apart from the refusal a rejected task answers. */
    private static final Outcome STEP_ANSWER = new Outcome(null, Refusal.UNKNOWN_ASSET);

    /** A world thread held still: a task waits here until the test runs it. */
    private static final class HeldTasks implements Executor {
        private final Deque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
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

    /** A stopping world: its execute throws instead of queueing. */
    private static final Executor STOPPING = task -> {
        throw new IllegalStateException("World thread is not accepting tasks");
    };

    @Test
    void aLandedWakeAnswersTheSpawnStepOnTheWorldThread() {
        HeldTasks world = new HeldTasks();
        CompletableFuture<Ref<ChunkStore>> woken = new CompletableFuture<>();
        int[] steps = {0};
        BiFunction<Ref<ChunkStore>, Throwable, Outcome> spawn = (sectionRef, error) -> {
            steps[0]++;
            return STEP_ANSWER;
        };

        CompletableFuture<Outcome> outcome = EncounterSpawner.whenWoken(woken, world, "Boss", spawn);
        woken.complete(null);
        assertFalse(outcome.isDone(), "never on the thread that landed the wake");
        assertEquals(1, world.runAll());

        assertEquals(STEP_ANSWER, outcome.join());
        assertEquals(1, steps[0]);
    }

    @Test
    void aFailedWakeIsAnsweredByTheSpawnStepAlone() {
        HeldTasks world = new HeldTasks();
        CompletableFuture<Ref<ChunkStore>> woken = new CompletableFuture<>();
        Throwable[] seen = {null};

        CompletableFuture<Outcome> outcome = EncounterSpawner.whenWoken(woken, world, "Boss", (sectionRef, error) -> {
            seen[0] = error;
            return STEP_ANSWER;
        });
        woken.completeExceptionally(new IllegalStateException("generation failed"));
        world.runAll();

        assertEquals(STEP_ANSWER, outcome.join(), "the step decides what a failed wake answers");
        assertTrue(String.valueOf(seen[0]).contains("generation failed"), String.valueOf(seen[0]));
    }

    @Test
    void aWorldThatTakesNoMoreTasksAnswersEngineFailedAndNeverRunsTheStep() {
        int[] steps = {0};
        BiFunction<Ref<ChunkStore>, Throwable, Outcome> spawn = (sectionRef, error) -> {
            steps[0]++;
            return STEP_ANSWER;
        };

        CompletableFuture<Ref<ChunkStore>> landing = new CompletableFuture<>();
        CompletableFuture<Outcome> outcome = EncounterSpawner.whenWoken(landing, STOPPING, "Boss", spawn);
        assertFalse(outcome.isDone(), "nothing is answered while the section loads");
        assertDoesNotThrow(() -> landing.complete(null), "the rejection never reaches the loading thread");
        assertEquals(new Outcome(null, Refusal.ENGINE_FAILED), outcome.getNow(null), "answered as the wake lands");

        CompletableFuture<Ref<ChunkStore>> failing = new CompletableFuture<>();
        CompletableFuture<Outcome> afterAFailure = EncounterSpawner.whenWoken(failing, STOPPING, "Boss", spawn);
        assertDoesNotThrow(() -> failing.completeExceptionally(new IllegalStateException("generation failed")));
        assertEquals(new Outcome(null, Refusal.ENGINE_FAILED), afterAFailure.getNow(null));

        CompletableFuture<Ref<ChunkStore>> landed = CompletableFuture.completedFuture(null);
        CompletableFuture<Outcome> alreadyLanded =
                assertDoesNotThrow(() -> EncounterSpawner.whenWoken(landed, STOPPING, "Boss", spawn));
        assertEquals(new Outcome(null, Refusal.ENGINE_FAILED), alreadyLanded.getNow(null),
                "a wake already over is answered during the call");

        assertEquals(0, steps[0], "the spawn step never runs");
    }
}
