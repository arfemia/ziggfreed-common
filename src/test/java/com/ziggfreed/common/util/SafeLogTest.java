package com.ziggfreed.common.util;

import org.junit.jupiter.api.Test;

/**
 * Every {@link SafeLog} level, with and without a cause, must be a no-op that never throws in a
 * log-manager-less unit JVM. That guard is the whole contract of this class.
 */
class SafeLogTest {

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
