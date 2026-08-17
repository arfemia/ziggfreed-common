package com.ziggfreed.common.objectives.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * A burst of repaint requests folds into ONE paint, run where the caller said; the next request
 * after the paint ran starts a new one; and an executor that refuses the task leaves the coalescer
 * free to try again rather than stuck believing a paint is queued.
 *
 * <p>The executor stands in for a player's world: on a server it drains its queue inside the same
 * tick, so "run the queue" here is "the tick ended".
 */
class RepaintCoalescerTest {

    /** A world stand-in: takes tasks and runs them only when told the tick ended. */
    private static final class ManualWorld implements Executor {

        final Deque<Runnable> queue = new ArrayDeque<>();

        @Override
        public void execute(Runnable task) {
            queue.add(task);
        }

        void endTick() {
            Runnable task;
            while ((task = queue.poll()) != null) {
                task.run();
            }
        }
    }

    @Test
    void manyRequestsInOneTickPaintOnceAtTheEndOfIt() {
        AtomicInteger paints = new AtomicInteger();
        RepaintCoalescer coalescer = new RepaintCoalescer(paints::incrementAndGet);
        ManualWorld world = new ManualWorld();

        for (int i = 0; i < 12; i++) {
            coalescer.request(world);
        }
        assertEquals(1, world.queue.size(), "one queued paint for the whole burst");
        assertEquals(0, paints.get(), "nothing has painted yet: the tick is still running");
        assertTrue(coalescer.isQueued());

        world.endTick();
        assertEquals(1, paints.get());
        assertFalse(coalescer.isQueued());
    }

    @Test
    void theNextRequestAfterAPaintStartsANewOne() {
        AtomicInteger paints = new AtomicInteger();
        RepaintCoalescer coalescer = new RepaintCoalescer(paints::incrementAndGet);
        ManualWorld world = new ManualWorld();

        coalescer.request(world);
        world.endTick();
        coalescer.request(world);
        coalescer.request(world);
        world.endTick();

        assertEquals(2, paints.get(), "one paint per tick that had a request in it");
    }

    @Test
    void nothingRunsWhenNothingWasAskedFor() {
        AtomicInteger paints = new AtomicInteger();
        new RepaintCoalescer(paints::incrementAndGet);
        ManualWorld world = new ManualWorld();
        world.endTick();
        assertEquals(0, paints.get());
        assertTrue(world.queue.isEmpty());
    }

    @Test
    void aWorldThatRefusesTheTaskLeavesTheNextRequestFreeToTry() {
        AtomicInteger paints = new AtomicInteger();
        RepaintCoalescer coalescer = new RepaintCoalescer(paints::incrementAndGet);
        Executor refusing = task -> {
            throw new IllegalStateException("world thread is not accepting tasks");
        };

        coalescer.request(refusing);
        assertFalse(coalescer.isQueued(), "a refused request must not leave a phantom paint queued");

        ManualWorld world = new ManualWorld();
        coalescer.request(world);
        world.endTick();
        assertEquals(1, paints.get());
    }

    @Test
    void aRequestArrivingWhileThePaintRunsQueuesOneMore() {
        ManualWorld world = new ManualWorld();
        AtomicInteger paints = new AtomicInteger();
        RepaintCoalescer[] self = new RepaintCoalescer[1];
        self[0] = new RepaintCoalescer(() -> {
            if (paints.incrementAndGet() == 1) {
                // State moved under the running paint: ask again, as an event on the same tick would.
                self[0].request(world);
            }
        });

        self[0].request(world);
        world.endTick();
        assertEquals(2, paints.get(), "the late request was not swallowed by the paint in flight");
    }
}
