package com.ziggfreed.common.util;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.CommonLog;

/**
 * The one guarded logging facade for ziggfreed-common. Every method wraps the raw
 * {@code CommonLog.LOGGER} fluent chain in {@code try/catch (Throwable)}: the
 * flogger LOGGER throws an {@link Error} in a log-manager-less unit JVM that escapes
 * {@code catch (Exception)}, so the guard is what lets engine-touching primitives be
 * unit-tested at all. Zero cost when nothing throws.
 *
 * <p>Every message is prefixed {@code [ziggfreed-common]}. Prefer a per-subsystem tag inside
 * the message (e.g. {@code "[interaction] ..."}) over adding a new method here.
 *
 * <p>This facade is used by NEW code only; the ~90 existing raw {@code
 * CommonLog.LOGGER} call sites across the mod are not retrofitted (out of scope
 * for this change).
 */
public final class SafeLog {

    private static final String PREFIX = "[ziggfreed-common] ";

    private SafeLog() {
    }

    /** Info-level log. Never throws. */
    public static void info(@Nonnull String message) {
        try {
            CommonLog.LOGGER.atInfo().log(PREFIX + message);
        } catch (Throwable ignored) {
            // no Hytale log manager (unit JVM)
        }
    }

    /** Info-level log with an attached cause. Never throws. */
    public static void info(@Nonnull String message, @Nullable Throwable cause) {
        try {
            CommonLog.LOGGER.atInfo().withCause(cause).log(PREFIX + message);
        } catch (Throwable ignored) {
            // no Hytale log manager (unit JVM)
        }
    }

    /** Warning-level log. Never throws. */
    public static void warn(@Nonnull String message) {
        try {
            CommonLog.LOGGER.atWarning().log(PREFIX + message);
        } catch (Throwable ignored) {
            // no Hytale log manager (unit JVM)
        }
    }

    /** Warning-level log with an attached cause. Never throws. */
    public static void warn(@Nonnull String message, @Nullable Throwable cause) {
        try {
            CommonLog.LOGGER.atWarning().withCause(cause).log(PREFIX + message);
        } catch (Throwable ignored) {
            // no Hytale log manager (unit JVM)
        }
    }

    /** Severe-level log. Never throws. */
    public static void severe(@Nonnull String message) {
        try {
            CommonLog.LOGGER.atSevere().log(PREFIX + message);
        } catch (Throwable ignored) {
            // no Hytale log manager (unit JVM)
        }
    }

    /** Severe-level log with an attached cause. Never throws. */
    public static void severe(@Nonnull String message, @Nullable Throwable cause) {
        try {
            CommonLog.LOGGER.atSevere().withCause(cause).log(PREFIX + message);
        } catch (Throwable ignored) {
            // no Hytale log manager (unit JVM)
        }
    }

    /** Fine-level log. Never throws. */
    public static void fine(@Nonnull String message) {
        try {
            CommonLog.LOGGER.atFine().log(PREFIX + message);
        } catch (Throwable ignored) {
            // no Hytale log manager (unit JVM)
        }
    }

    /** Fine-level log with an attached cause. Never throws. */
    public static void fine(@Nonnull String message, @Nullable Throwable cause) {
        try {
            CommonLog.LOGGER.atFine().withCause(cause).log(PREFIX + message);
        } catch (Throwable ignored) {
            // no Hytale log manager (unit JVM)
        }
    }
}
