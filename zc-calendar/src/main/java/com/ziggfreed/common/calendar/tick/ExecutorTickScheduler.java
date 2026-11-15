package com.ziggfreed.common.calendar.tick;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;

import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.util.ZigScheduler;

/**
 * The production scheduler: the library's own scheduler thread ({@link ZigScheduler}). In a JVM with no
 * server (a unit test, a headless tool) it is not running, and then the calendar does not tick: one
 * warning, never a throw.
 */
public final class ExecutorTickScheduler implements TickScheduler {

    public static final ExecutorTickScheduler INSTANCE = new ExecutorTickScheduler();

    private ExecutorTickScheduler() {
    }

    @Override
    @Nonnull
    public Cancellable repeat(@Nonnull Runnable task, long initialDelayMs, long periodMs) {
        try {
            ScheduledFuture<?> future = ZigScheduler.scheduleAtFixedRate(guarded(task),
                    initialDelayMs, periodMs, TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        } catch (Throwable t) {
            SafeLog.warn("[calendar] the library's scheduler is not running, so the calendar will not notice a"
                    + " start or an end: " + t.getMessage());
            return () -> { };
        }
    }

    @Override
    public void runSoon(@Nonnull Runnable task) {
        try {
            ZigScheduler.execute(guarded(task));
        } catch (Throwable t) {
            SafeLog.warn("[calendar] the library's scheduler is not running, so a calendar change waits for the next"
                    + " tick: " + t.getMessage());
        }
    }

    /** A run that throws must not stop the repeat: a fixed-rate task is dropped after its first throw. */
    @Nonnull
    private static Runnable guarded(@Nonnull Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable t) {
                SafeLog.warn("[calendar] a calendar tick failed", t);
            }
        };
    }
}
