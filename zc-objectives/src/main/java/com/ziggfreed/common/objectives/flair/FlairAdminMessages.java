package com.ziggfreed.common.objectives.flair;

import java.awt.Color;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.ziggfreed.common.i18n.Msg;

/**
 * Every line the {@code /zigflair} family says, by the same two rules the progression family keeps:
 * a sentence is a KEY resolved on the reader's own client, and an id, a player name or a count is a
 * raw argument, because those are DATA that read the same in every language and a translated one
 * would stop naming the thing an administrator has to go and edit.
 *
 * <p><b>Keys live in {@code ziggfreedcommon.flair.admin.lang}</b>, the admin sibling of the
 * player-facing {@code ziggfreedcommon.flair.lang}, so the in-file key drops the
 * {@code ziggfreedcommon.flair.admin.} segment the filename already carries. The DESCRIPTION
 * strings a command and its arguments are constructed with are keys too: the engine resolves a
 * command description through its own localization module.
 */
public final class FlairAdminMessages {

    /** The key family every line here resolves under (the shipped file name, minus {@code .lang}). */
    public static final String PREFIX = "ziggfreedcommon.flair.admin.";

    private static final Color HEADING = new Color(0xFFCC66);
    private static final Color DETAIL = new Color(0xAAAAAA);
    private static final Color BAD = new Color(0xFF5555);
    private static final Color GOOD = new Color(0x77DD77);
    private static final Color WARN = new Color(0xFFAA55);

    private FlairAdminMessages() {
    }

    /** What a command or one of its arguments is FOR, resolved by the engine's own help. */
    @Nonnull
    public static String desc(@Nonnull String what) {
        return PREFIX + "desc." + what;
    }

    /** A heading line: what the rows under it are. */
    public static void heading(@Nonnull CommandContext ctx, @Nonnull String key, @Nonnull Object... args) {
        ctx.sendMessage(Msg.key(PREFIX + key, args).color(HEADING));
    }

    /** A row, or anything else that is detail rather than an answer. */
    public static void detail(@Nonnull CommandContext ctx, @Nonnull String key, @Nonnull Object... args) {
        ctx.sendMessage(Msg.key(PREFIX + key, args).color(DETAIL));
    }

    /** Something was done. */
    public static void done(@Nonnull CommandContext ctx, @Nonnull String key, @Nonnull Object... args) {
        ctx.sendMessage(Msg.key(PREFIX + key, args).color(GOOD));
    }

    /** Something was done, but the sender should know a thing about it. */
    public static void warned(@Nonnull CommandContext ctx, @Nonnull String key, @Nonnull Object... args) {
        ctx.sendMessage(Msg.key(PREFIX + key, args).color(WARN));
    }

    /** Something was refused, and why. */
    public static void refused(@Nonnull CommandContext ctx, @Nonnull String key, @Nonnull Object... args) {
        ctx.sendMessage(Msg.key(PREFIX + key, args).color(BAD));
    }
}
