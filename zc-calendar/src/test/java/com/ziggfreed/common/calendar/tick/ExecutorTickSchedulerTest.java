package com.ziggfreed.common.calendar.tick;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

/** In a JVM with no server, asking the production scheduler to tick fails soft: a handle, never a throw. */
class ExecutorTickSchedulerTest {

    @Test
    void withNoServerTheSchedulerFailsSoftAndItsHandleCancels() {
        TickScheduler.Cancellable handle = assertDoesNotThrow(
                () -> ExecutorTickScheduler.INSTANCE.repeat(() -> { }, 60_000L, 60_000L));
        assertNotNull(handle);
        assertDoesNotThrow(handle::cancel);
        assertDoesNotThrow(() -> ExecutorTickScheduler.INSTANCE.runSoon(() -> { }));
    }
}
