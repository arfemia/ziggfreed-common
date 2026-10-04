package com.ziggfreed.common.npc;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;

import javax.annotation.Nonnull;

/**
 * A world thread held still, for tests of code that continues on the world executor: a task waits
 * here until the test runs it, so a test can tell "queued for the world thread" from "ran on the
 * thread that completed a future".
 */
public final class HeldWorldThread implements Executor {

    private final Deque<Runnable> tasks = new ArrayDeque<>();

    @Override
    public void execute(@Nonnull Runnable task) {
        tasks.add(task);
    }

    /** How many tasks wait. */
    public int pending() {
        return tasks.size();
    }

    /** Run every waiting task, including any a task queues while it runs; answers how many ran. */
    public int runAll() {
        int ran = 0;
        for (Runnable task = tasks.poll(); task != null; task = tasks.poll()) {
            task.run();
            ran++;
        }
        return ran;
    }
}
