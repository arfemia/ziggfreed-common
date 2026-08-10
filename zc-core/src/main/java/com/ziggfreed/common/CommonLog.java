package com.ziggfreed.common;

import com.hypixel.hytale.logger.HytaleLogger;

/**
 * The library's one logger handle.
 *
 * <p>Every package in ziggfreed-common logs through {@link #LOGGER}. Holding it here rather than on
 * the plugin class keeps a logging call from dragging the whole plugin entry point (and everything
 * that entry point wires) into a package's dependency closure, so each domain package stays free to
 * stand on its own.
 *
 * <p><b>The logger name is pinned as a literal string on purpose.</b> {@code HytaleLogger} derives a
 * logger's name from the enclosing class and then keeps only the simple name, so
 * {@code get("ZiggfreedCommonPlugin")} is the same handle - and prints the same log prefix - that
 * {@code forEnclosingClass()} yields from the plugin class itself. Renaming this string (or moving
 * the field to a differently-named class and letting the name be derived) changes what every one of
 * this library's log lines is tagged with, and any log filter or grep a server owner has set up
 * against it. Leave it alone.
 *
 * <p><b>Guard this logger on any path a unit test can reach.</b> The underlying flogger backend
 * throws in a log-manager-less unit JVM, and it throws an {@code Error} that escapes
 * {@code catch (Exception)}. Either route the call through
 * {@link com.ziggfreed.common.util.SafeLog} (which catches {@code Throwable} for you and is the
 * better default) or wrap it in a {@code try/catch (Throwable)} of your own.
 */
public final class CommonLog {

    /** The shared logger for every ziggfreed-common package. */
    public static final HytaleLogger LOGGER = HytaleLogger.get("ZiggfreedCommonPlugin");

    private CommonLog() {
    }
}
