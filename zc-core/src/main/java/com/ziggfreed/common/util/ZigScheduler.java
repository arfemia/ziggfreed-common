package com.ziggfreed.common.util;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The family's delayed and repeating work: one daemon thread the library owns, started by the library
 * plugin's {@code setup()} and stopped by its {@code shutdown()}. It stands in for the engine's shared
 * {@code HytaleServer.SCHEDULED_EXECUTOR} and {@code World.scheduleAfter}, which Update 8 removes (its
 * per-plugin {@code TaskRegistry.schedule} does not exist on Update 7), so one call works on both lines.
 * The signatures follow Update 8's {@code TaskRegistry}, so a later move onto it is mechanical.
 * <ul>
 *     <li>A run happens on the scheduler thread; a task that touches a world or a store hops with
 *     {@code world.execute}, or passes the world as the executor of {@link #schedule(Runnable, long,
 *     TimeUnit, Executor)}.</li>
 *     <li>A task that throws is logged once through {@link SafeLog}, and a repeating one stops, as on any
 *     {@code ScheduledExecutorService}.</li>
 *     <li>Every scheduling call throws {@link RejectedExecutionException} while the scheduler is not
 *     running: in a JVM with no server (a unit test, a headless tool) and after the library shuts down. A
 *     caller with a synchronous fallback (a debounced file write that writes at once) catches it.</li>
 * </ul>
 */
public final class ZigScheduler {

    /** The scheduler thread's name, in the family's {@code ziggfreed-<job>} daemon convention. */
    public static final String THREAD_NAME = "ziggfreed-scheduler";

    private static final Object LOCK = new Object();
    @Nullable
    private static ScheduledThreadPoolExecutor executor;

    private ZigScheduler() {
    }

    /** Start the scheduler thread; the library plugin's {@code setup()} calls this first. Idempotent. */
    public static void start() {
        synchronized (LOCK) {
            if (executor != null) {
                return;
            }
            ScheduledThreadPoolExecutor pool = new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, THREAD_NAME);
                t.setDaemon(true);
                return t;
            });
            pool.setRemoveOnCancelPolicy(true);
            pool.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            pool.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
            executor = pool;
        }
    }

    /**
     * Stop the scheduler thread and drop every task still waiting; the library plugin's {@code shutdown()}
     * calls this. A scheduling call after it throws {@link RejectedExecutionException}, and a later
     * {@link #start()} (a plugin reload) starts a fresh thread.
     */
    public static void shutdown() {
        ScheduledThreadPoolExecutor toStop;
        synchronized (LOCK) {
            toStop = executor;
            executor = null;
        }
        if (toStop == null) {
            return;
        }
        int dropped = toStop.shutdownNow().size();
        if (dropped > 0) {
            SafeLog.info("[scheduler] " + dropped + " waiting task(s) dropped at shutdown");
        }
    }

    /** Whether a scheduling call is accepted now. */
    public static boolean isRunning() {
        synchronized (LOCK) {
            return executor != null;
        }
    }

    /**
     * Run {@code task} once on the scheduler thread after {@code delay}.
     *
     * @throws RejectedExecutionException while the scheduler is not running
     */
    @Nonnull
    public static ScheduledFuture<?> schedule(@Nonnull Runnable task, long delay, @Nonnull TimeUnit unit) {
        return pool().schedule(logged(task), delay, unit);
    }

    /**
     * Hand {@code task} to {@code executor} (a {@code World}, usually) after {@code delay}, in place of the
     * engine's {@code World.scheduleAfter}: dropped quietly when the executor no longer takes tasks (a world
     * shutting down). Cancelling the future stops the hand-off; once handed off, the run is the executor's.
     *
     * @throws RejectedExecutionException while the scheduler is not running
     */
    @Nonnull
    public static ScheduledFuture<?> schedule(@Nonnull Runnable task, long delay, @Nonnull TimeUnit unit,
            @Nonnull Executor executor) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(executor, "executor");
        return pool().schedule(() -> {
            try {
                executor.execute(logged(task));
            } catch (Throwable t) {
                SafeLog.fine("[scheduler] " + executor + " no longer takes tasks, so a scheduled one was dropped: "
                        + t.getMessage());
            }
        }, delay, unit);
    }

    /**
     * Run {@code task} on the scheduler thread after {@code initialDelay}, then every {@code period}. A run
     * that throws stops the repeat, so a task that must keep going catches its own failures.
     *
     * @throws RejectedExecutionException while the scheduler is not running
     */
    @Nonnull
    public static ScheduledFuture<?> scheduleAtFixedRate(@Nonnull Runnable task, long initialDelay, long period,
            @Nonnull TimeUnit unit) {
        return pool().scheduleAtFixedRate(logged(task), initialDelay, period, unit);
    }

    /**
     * Run {@code task} on the scheduler thread after {@code initialDelay}, then {@code delay} after each run
     * ends. A run that throws stops the repeat.
     *
     * @throws RejectedExecutionException while the scheduler is not running
     */
    @Nonnull
    public static ScheduledFuture<?> scheduleWithFixedDelay(@Nonnull Runnable task, long initialDelay, long delay,
            @Nonnull TimeUnit unit) {
        return pool().scheduleWithFixedDelay(logged(task), initialDelay, delay, unit);
    }

    /**
     * Run {@code task} on the scheduler thread as soon as it is free.
     *
     * @throws RejectedExecutionException while the scheduler is not running
     */
    public static void execute(@Nonnull Runnable task) {
        pool().execute(logged(task));
    }

    @Nonnull
    private static ScheduledThreadPoolExecutor pool() {
        synchronized (LOCK) {
            if (executor == null) {
                throw new RejectedExecutionException("the library's scheduler is not running (no server, or the"
                        + " library has shut down)");
            }
            return executor;
        }
    }

    /** The task, logging a failure before the executor records it, so a dropped repeat is never silent. */
    @Nonnull
    private static Runnable logged(@Nonnull Runnable task) {
        Objects.requireNonNull(task, "task");
        return () -> {
            try {
                task.run();
            } catch (Throwable t) {
                SafeLog.warn("[scheduler] a scheduled task failed", t);
                throw t;
            }
        };
    }
}
