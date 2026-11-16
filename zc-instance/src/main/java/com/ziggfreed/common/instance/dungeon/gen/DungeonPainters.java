package com.ziggfreed.common.instance.dungeon.gen;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.util.SafeLog;

/**
 * The worker threads a generated dungeon's chunks are painted on, and its runs laid out and prepared on, so neither
 * ever costs a world thread. Started at the library's setup ({@link #start}) and stopped at its shutdown
 * ({@link #shutdown}): the stop takes no new work, lets a chunk already being painted finish (up to
 * {@link #STOP_WAIT_SECONDS}), and never interrupts one, so no thread outlives a stopped server by more than that
 * wait.
 *
 * <p>Work handed here while the pool is down (before setup, after shutdown, in a unit-test JVM) runs on the caller's
 * own thread instead ({@link #run}), so nothing is ever lost; the run's measurement line counts those chunks apart.
 */
public final class DungeonPainters {

    /** How many painter threads run (lead's call from the spike: two kept up at 4.2 ms a chunk). */
    static final int THREADS = 2;

    /** How long the stop waits for a chunk already being painted. */
    static final long STOP_WAIT_SECONDS = 2L;

    private static final String THREAD_PREFIX = "zc-dungeon-paint-";
    private static final Object LOCK = new Object();

    @Nullable
    private static ExecutorService pool;

    private DungeonPainters() {
    }

    /** Start the painter threads. Idempotent; a later start after {@link #shutdown} (a plugin reload) starts fresh ones. */
    public static void start() {
        synchronized (LOCK) {
            if (pool != null) {
                return;
            }
            AtomicInteger next = new AtomicInteger();
            pool = Executors.newFixedThreadPool(THREADS, task -> {
                Thread thread = new Thread(task, THREAD_PREFIX + next.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
        }
    }

    /**
     * Stop the painter threads: no new work is taken, work already queued or running finishes, and the stop waits up
     * to {@link #STOP_WAIT_SECONDS} for it, never interrupting a chunk half painted. Harmless twice, and never throws.
     */
    public static void shutdown() {
        ExecutorService stopping;
        synchronized (LOCK) {
            stopping = pool;
            pool = null;
        }
        if (stopping == null) {
            return;
        }
        stopping.shutdown();
        try {
            if (!stopping.awaitTermination(STOP_WAIT_SECONDS, TimeUnit.SECONDS)) {
                SafeLog.warn("[dungeon] a painter was still painting " + STOP_WAIT_SECONDS
                        + " seconds into the stop; its thread ends once that chunk is done");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Whether the painter threads take work now. */
    public static boolean running() {
        synchronized (LOCK) {
            return pool != null;
        }
    }

    /**
     * Run {@code task} on a painter thread, or on the caller's own thread when the pool is down or refuses it.
     * Answers true when it went to a painter.
     */
    static boolean run(@Nonnull Runnable task) {
        ExecutorService target;
        synchronized (LOCK) {
            target = pool;
        }
        if (target != null) {
            try {
                target.execute(task);
                return true;
            } catch (RejectedExecutionException stopping) {
                // The pool is stopping: the work still happens, here.
            }
        }
        task.run();
        return false;
    }
}
