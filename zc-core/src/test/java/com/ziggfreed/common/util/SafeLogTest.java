package com.ziggfreed.common.util;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.logging.LogManager;

import org.junit.jupiter.api.Test;

/**
 * Every {@link SafeLog} level, with and without a cause, must be a no-op that never throws in a
 * log-manager-less unit JVM. That guard is the whole contract of this class.
 *
 * <p>So this class must run in that JVM: the default {@code test} task, never the
 * {@code engineItemTest} task that starts under the engine's own log manager (see
 * {@code gradle/zc-module.gradle}). Under that manager the raw logger works, the guard is never
 * reached, and every level test would pass without proving anything, so
 * {@code runsWhereTheGuardIsWhatKeepsTheCallAlive} fails there instead.
 */
class SafeLogTest {

    private static final String ENGINE_LOG_MANAGER = "com.hypixel.hytale.logger.backend.HytaleLogManager";

    @Test
    void runsWhereTheGuardIsWhatKeepsTheCallAlive() {
        assertNotEquals(ENGINE_LOG_MANAGER, LogManager.getLogManager().getClass().getName(),
                "SafeLog's guard is only exercised with no engine log manager installed");
    }

    @Test
    void infoNeverThrows() {
        SafeLog.info("message");
        SafeLog.info("message", new IllegalStateException("cause"));
        SafeLog.info("message", (Throwable) null);
    }

    @Test
    void warnNeverThrows() {
        SafeLog.warn("message");
        SafeLog.warn("message", new IllegalStateException("cause"));
        SafeLog.warn("message", (Throwable) null);
    }

    @Test
    void severeNeverThrows() {
        SafeLog.severe("message");
        SafeLog.severe("message", new IllegalStateException("cause"));
        SafeLog.severe("message", (Throwable) null);
    }

    @Test
    void fineNeverThrows() {
        SafeLog.fine("message");
        SafeLog.fine("message", new IllegalStateException("cause"));
        SafeLog.fine("message", (Throwable) null);
    }
}
