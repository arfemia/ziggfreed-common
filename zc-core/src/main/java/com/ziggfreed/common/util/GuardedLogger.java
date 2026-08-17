package com.ziggfreed.common.util;

import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.logger.HytaleLogger;

/**
 * A guarded logging facade over a {@link HytaleLogger}, holding an optional message prefix. Every
 * method wraps the raw fluent chain in {@code try/catch (Throwable)}: the flogger LOGGER throws an
 * {@link Error} in a log-manager-less unit JVM that escapes {@code catch (Exception)}, so the guard
 * is what lets an engine-touching primitive be unit-tested at all. Zero cost when nothing throws.
 *
 * <p><b>The logger handle is taken as a {@link Supplier}, not a resolved instance, and that is
 * load-bearing.</b> Several callers' underlying logger is itself a static field whose OWN class
 * initializer can throw in a log-manager-less unit JVM (e.g. {@code CommonLog.LOGGER}'s
 * {@code HytaleLogger.get(...)} call). Resolving that field eagerly - say, in a caller's own static
 * field initializer building a {@code GuardedLogger} - touches it OUTSIDE any try/catch, so the
 * failure propagates as an uncaught {@code ExceptionInInitializerError} out of the caller's class
 * init instead of being swallowed here. Taking a supplier defers that first touch to INSIDE each
 * guarded method body below, exactly where the try/catch already lives, so a caller can safely build
 * its {@code GuardedLogger} as a plain {@code static final} field (e.g.
 * {@code new GuardedLogger(() -> CommonLog.LOGGER, "...")}) without eagerly forcing the referenced
 * class to initialize.
 *
 * <p>This is the ONE shared implementation behind the logging facade of every mod that already
 * depends on this library ({@code util.SafeLog} here and in the MMO and Kweebec Nightmare,
 * {@code util.Log} in RPG Stations) - each wraps a single instance of this class over its own
 * plugin's logger and prefix, so the guard shape lives in exactly one place instead of being
 * hand-rolled once per mod. A consumer facade keeps its own name and ownership; only its method
 * bodies delegate here. A mod that deliberately takes NO dependency on this library keeps its own
 * hand-rolled copy of the guard, and that is the right call there: converging it would hand a
 * single-jar mod a dependency it exists to avoid.
 */
public final class GuardedLogger {

    private final Supplier<HytaleLogger> logger;
    private final String prefix;

    /**
     * @param logger supplies the underlying flogger handle to guard, invoked freshly inside each
     *               guarded method call (never cached here) so a supplier whose first resolution
     *               throws is safe to pass.
     * @param prefix prepended verbatim to every logged message (pass {@code ""} for none).
     */
    public GuardedLogger(@Nonnull Supplier<HytaleLogger> logger, @Nonnull String prefix) {
        this.logger = logger;
        this.prefix = prefix;
    }

    /**
     * Applies the prefix, allocating nothing when there is none. An empty prefix is the common case
     * (three of the four facades built on this class pass {@code ""}), and a plain
     * {@code prefix + message} would still allocate a fresh String there - paid on every call at
     * every level, including a disabled {@code fine}/{@code finer} whose argument is built before
     * the level check can no-op it. Hot per-tick paths log through these facades, so the no-prefix
     * case passes the caller's own String straight through.
     */
    @Nonnull
    private String msg(@Nonnull String message) {
        return prefix.isEmpty() ? message : prefix + message;
    }

    /** Info-level log. Never throws. */
    public void info(@Nonnull String message) {
        try {
            logger.get().atInfo().log(msg(message));
        } catch (Throwable ignored) {
            // no Hytale log manager (unit JVM)
        }
    }

    /** Info-level log with an attached cause. Never throws. */
    public void info(@Nonnull String message, @Nullable Throwable cause) {
        try {
            logger.get().atInfo().withCause(cause).log(msg(message));
        } catch (Throwable ignored) {
            // no Hytale log manager (unit JVM)
        }
    }

    /** Warning-level log. Never throws. */
    public void warn(@Nonnull String message) {
        try {
            logger.get().atWarning().log(msg(message));
        } catch (Throwable ignored) {
            // no Hytale log manager (unit JVM)
        }
    }

    /** Warning-level log with an attached cause. Never throws. */
    public void warn(@Nonnull String message, @Nullable Throwable cause) {
        try {
            logger.get().atWarning().withCause(cause).log(msg(message));
        } catch (Throwable ignored) {
            // no Hytale log manager (unit JVM)
        }
    }

    /** Severe-level log. Never throws. */
    public void severe(@Nonnull String message) {
        try {
            logger.get().atSevere().log(msg(message));
        } catch (Throwable ignored) {
            // no Hytale log manager (unit JVM)
        }
    }

    /** Severe-level log with an attached cause. Never throws. */
    public void severe(@Nonnull String message, @Nullable Throwable cause) {
        try {
            logger.get().atSevere().withCause(cause).log(msg(message));
        } catch (Throwable ignored) {
            // no Hytale log manager (unit JVM)
        }
    }

    /** Fine-level log. Never throws. */
    public void fine(@Nonnull String message) {
        try {
            logger.get().atFine().log(msg(message));
        } catch (Throwable ignored) {
            // no Hytale log manager (unit JVM)
        }
    }

    /** Fine-level log with an attached cause. Never throws. */
    public void fine(@Nonnull String message, @Nullable Throwable cause) {
        try {
            logger.get().atFine().withCause(cause).log(msg(message));
        } catch (Throwable ignored) {
            // no Hytale log manager (unit JVM)
        }
    }

    /** Finer-level log. Never throws. */
    public void finer(@Nonnull String message) {
        try {
            logger.get().atFiner().log(msg(message));
        } catch (Throwable ignored) {
            // no Hytale log manager (unit JVM)
        }
    }

    /** Finer-level log with an attached cause. Never throws. */
    public void finer(@Nonnull String message, @Nullable Throwable cause) {
        try {
            logger.get().atFiner().withCause(cause).log(msg(message));
        } catch (Throwable ignored) {
            // no Hytale log manager (unit JVM)
        }
    }
}
