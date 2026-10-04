package com.ziggfreed.common.calendar.tick;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import javax.annotation.Nonnull;

/** A scheduler a test drives by hand: a period passes when the test says so, and "soon" is when it drains. */
final class ManualTickScheduler implements TickScheduler {

    private final List<Runnable> repeating = new ArrayList<>();
    private final Deque<Runnable> soon = new ArrayDeque<>();
    private long lastPeriodMs = -1L;

    @Override
    @Nonnull
    public Cancellable repeat(@Nonnull Runnable task, long initialDelayMs, long periodMs) {
        repeating.add(task);
        lastPeriodMs = periodMs;
        return () -> repeating.remove(task);
    }

    @Override
    public void runSoon(@Nonnull Runnable task) {
        soon.add(task);
    }

    /** One period passes: every repeating task runs once. */
    void tick() {
        for (Runnable task : List.copyOf(repeating)) {
            task.run();
        }
    }

    /** Run everything asked to run soon. */
    void drain() {
        while (!soon.isEmpty()) {
            soon.poll().run();
        }
    }

    int repeatingCount() {
        return repeating.size();
    }

    long lastPeriodMs() {
        return lastPeriodMs;
    }
}
