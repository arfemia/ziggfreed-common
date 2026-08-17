package com.ziggfreed.common.util;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.CommonLog;

/**
 * The one guarded logging facade for ziggfreed-common: a thin static wrapper over one shared
 * {@link GuardedLogger} instance holding {@link CommonLog#LOGGER} plus the {@code
 * [ziggfreed-common] } prefix. See {@link GuardedLogger} for the guard itself - the flogger LOGGER
 * throws an {@link Error} in a log-manager-less unit JVM that escapes {@code catch (Exception)}, so
 * the guard is what lets engine-touching primitives be unit-tested at all. Zero cost when nothing
 * throws.
 *
 * <p>Prefer a per-subsystem tag inside the message (e.g. {@code "[interaction] ..."}) over adding a
 * new method here.
 *
 * <p>This facade is used by NEW code only; the ~90 existing raw {@code
 * CommonLog.LOGGER} call sites across the mod are not retrofitted (out of scope
 * for this change).
 */
public final class SafeLog {

    private static final GuardedLogger DELEGATE =
            new GuardedLogger(() -> CommonLog.LOGGER, "[ziggfreed-common] ");

    private SafeLog() {
    }

    /** Info-level log. Never throws. */
    public static void info(@Nonnull String message) {
        DELEGATE.info(message);
    }

    /** Info-level log with an attached cause. Never throws. */
    public static void info(@Nonnull String message, @Nullable Throwable cause) {
        DELEGATE.info(message, cause);
    }

    /** Warning-level log. Never throws. */
    public static void warn(@Nonnull String message) {
        DELEGATE.warn(message);
    }

    /** Warning-level log with an attached cause. Never throws. */
    public static void warn(@Nonnull String message, @Nullable Throwable cause) {
        DELEGATE.warn(message, cause);
    }

    /** Severe-level log. Never throws. */
    public static void severe(@Nonnull String message) {
        DELEGATE.severe(message);
    }

    /** Severe-level log with an attached cause. Never throws. */
    public static void severe(@Nonnull String message, @Nullable Throwable cause) {
        DELEGATE.severe(message, cause);
    }

    /** Fine-level log. Never throws. */
    public static void fine(@Nonnull String message) {
        DELEGATE.fine(message);
    }

    /** Fine-level log with an attached cause. Never throws. */
    public static void fine(@Nonnull String message, @Nullable Throwable cause) {
        DELEGATE.fine(message, cause);
    }
}
