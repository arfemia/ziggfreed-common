package com.ziggfreed.common.calendar.tick;

import javax.annotation.Nonnull;

/**
 * The clock the calendar's minute check runs on. A seam so a test drives every period by hand
 * ({@code ManualTickScheduler}); production is {@link ExecutorTickScheduler}.
 */
public interface TickScheduler {

    /** Run {@code task} every {@code periodMs}, first after {@code initialDelayMs}; the handle stops it. */
    @Nonnull
    Cancellable repeat(@Nonnull Runnable task, long initialDelayMs, long periodMs);

    /** Run {@code task} once, as soon as the scheduler can, never on the calling thread's stack. */
    void runSoon(@Nonnull Runnable task);

    /** A handle to a repeating task. */
    interface Cancellable {

        /** Stop the task if it is still repeating. Idempotent. */
        void cancel();
    }
}
