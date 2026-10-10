package com.ziggfreed.common.playerstats.command;

import java.awt.Color;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.ziggfreed.common.i18n.Msg;

/**
 * Every line the {@code /zigstats} family says: a sentence is a KEY resolved on the reader's client, and
 * a stat key, a player name or a count is an argument, because those are data an administrator types
 * back. Keys live in {@code ziggfreedcommon.stats.lang} under {@code command.}; a command and argument
 * DESCRIPTION is a key too, which the engine's help resolves.
 */
public final class StatsCommandMessages {

    /** The key family every line here resolves under. */
    public static final String PREFIX = "ziggfreedcommon.stats.command.";

    private static final Color HEADING = new Color(0xFFCC66);
    private static final Color DETAIL = new Color(0xAAAAAA);
    private static final Color BAD = new Color(0xFF5555);

    private StatsCommandMessages() {
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

    /** Something was refused, and why. */
    public static void refused(@Nonnull CommandContext ctx, @Nonnull String key, @Nonnull Object... args) {
        ctx.sendMessage(Msg.key(PREFIX + key, args).color(BAD));
    }
}
