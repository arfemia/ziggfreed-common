package com.ziggfreed.common.ui.hud.command;

import java.awt.Color;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.ziggfreed.common.i18n.Msg;

/**
 * Every line the HUD family says, on the settings page and at the command line, and the rule it
 * says them by: a sentence is a KEY resolved on the reader's own client, and an id or a panel's
 * name is an argument. A panel or a spot is named by the nested {@link Message} its own file
 * answers, passed as an argument rather than resolved to a string here, so it reads in the reader's
 * language whatever language the server runs in.
 *
 * <p><b>Keys live in {@code ziggfreedcommon.hud.lang}</b>, so the in-file key drops the
 * {@code ziggfreedcommon.hud.} segment the filename already carries. The DESCRIPTION strings a
 * command and its arguments are constructed with are keys too: the engine resolves a command
 * description through its own localization module, so a plain English one renders to the reader as
 * the raw text nobody translated.
 */
public final class HudMessages {

    /** The key family every line here resolves under (the shipped file name, minus {@code .lang}). */
    public static final String PREFIX = "ziggfreedcommon.hud.";

    private static final Color HEADING = new Color(0xFFCC66);
    private static final Color DETAIL = new Color(0xAAAAAA);
    private static final Color BAD = new Color(0xFF5555);
    private static final Color GOOD = new Color(0x77DD77);

    private HudMessages() {
    }

    /** The full registered key of {@code key}, for a control the client resolves by message id. */
    @Nonnull
    public static String key(@Nonnull String key) {
        return PREFIX + key;
    }

    /** What a command or one of its arguments is FOR, resolved by the engine's own help. */
    @Nonnull
    public static String desc(@Nonnull String what) {
        return PREFIX + "desc." + what;
    }

    /** A line of this family as a client-resolved message, for a page or a toast. */
    @Nonnull
    public static Message line(@Nonnull String key, @Nonnull Object... args) {
        return Msg.key(PREFIX + key, args);
    }

    // ==================== saying it at the command line ====================

    /** A heading line: what the rows under it are. */
    public static void heading(@Nonnull CommandContext ctx, @Nonnull String key, @Nonnull Object... args) {
        ctx.sendMessage(line(key, args).color(HEADING));
    }

    /** A row, or anything else that is detail rather than an answer. */
    public static void detail(@Nonnull CommandContext ctx, @Nonnull String key, @Nonnull Object... args) {
        ctx.sendMessage(line(key, args).color(DETAIL));
    }

    /** Something was done. */
    public static void done(@Nonnull CommandContext ctx, @Nonnull String key, @Nonnull Object... args) {
        ctx.sendMessage(line(key, args).color(GOOD));
    }

    /** Something was refused, and this is why. */
    public static void refused(@Nonnull CommandContext ctx, @Nonnull String key, @Nonnull Object... args) {
        ctx.sendMessage(line(key, args).color(BAD));
    }
}
