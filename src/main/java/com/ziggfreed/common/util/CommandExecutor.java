package com.ziggfreed.common.util;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.console.ConsoleSender;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.ZiggfreedCommonPlugin;

/**
 * Executes commands programmatically as the server console or as a player, via the
 * engine {@code CommandManager}. The config-free, mod-agnostic execute core (the MMO
 * placeholder substitution that coupled to skill/i18n is intentionally NOT lifted -
 * a consumer builds its own command string before calling here).
 */
public final class CommandExecutor {

    private CommandExecutor() {
    }

    /**
     * Execute a command as the server console.
     *
     * @param command the command to execute (with or without a leading slash)
     * @return true if execution succeeded, false otherwise
     */
    public static boolean executeAsConsole(@Nonnull String command) {
        return executeAsConsole(command, null);
    }

    /**
     * Execute a command as the server console with a target username for logging.
     * The username is for logging context only; the command should already have any
     * player references substituted by the caller.
     *
     * @param command  the command to execute (with or without a leading slash)
     * @param username the target player's username (logging context only), or null
     * @return true if execution succeeded, false otherwise
     */
    public static boolean executeAsConsole(@Nonnull String command, @Nullable String username) {
        try {
            String cmdWithoutSlash = command.startsWith("/") ? command.substring(1) : command;
            CommandManager.get().handleCommand(ConsoleSender.INSTANCE, cmdWithoutSlash);
            logInfo("[Console Command] " + cmdWithoutSlash
                    + (username != null ? " (for: " + username + ")" : ""));
            return true;
        } catch (Exception e) {
            logWarning("Console command execution failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Execute a command as a specific player.
     *
     * @param playerRef the player to execute the command as; null = no-op false
     * @param command   the command to execute (with or without a leading slash)
     * @return true if execution succeeded, false otherwise
     */
    public static boolean executeAsPlayer(@Nullable PlayerRef playerRef, @Nonnull String command) {
        if (playerRef == null) {
            logWarning("Cannot execute player command - playerRef is null");
            return false;
        }
        try {
            String cmdWithoutSlash = command.startsWith("/") ? command.substring(1) : command;
            CommandManager.get().handleCommand(playerRef, cmdWithoutSlash);
            logInfo("[Player Command: " + playerRef.getUsername() + "] " + cmdWithoutSlash);
            return true;
        } catch (Exception e) {
            logWarning("Player command execution failed: " + e.getMessage());
            return false;
        }
    }

    // The flogger-backed LOGGER throws in a log-manager-less unit JVM; guard every
    // call so a test that reaches this util cannot crash with a logging Error.
    private static void logInfo(@Nonnull String msg) {
        try {
            ZiggfreedCommonPlugin.LOGGER.atInfo().log(msg);
        } catch (Throwable ignored) {
            // no log manager (unit test); the command still ran
        }
    }

    private static void logWarning(@Nonnull String msg) {
        try {
            ZiggfreedCommonPlugin.LOGGER.atWarning().log(msg);
        } catch (Throwable ignored) {
            // no log manager (unit test)
        }
    }
}
