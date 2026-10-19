package com.ziggfreed.common.track.command;

import java.awt.Color;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.track.boost.BoostText;

/**
 * Every line {@code /zigboost} says: a sentence is a key in {@code ziggfreedcommon.boost.lang} (under
 * {@code command.}), resolved on the reader's own client, and an id, a player's name or a number is an argument. A
 * command's and an argument's DESCRIPTION is a key too, which the engine's own help resolves.
 */
final class BoostCommandMessages {

    /** The key family every line here resolves under. */
    static final String PREFIX = BoostText.PREFIX + "command.";

    private static final Color BAD = new Color(0xFF5555);
    private static final Color GOOD = new Color(0x77DD77);

    private BoostCommandMessages() {
    }

    /** What a command or one of its arguments is FOR, resolved by the engine's own help. */
    @Nonnull
    static String desc(@Nonnull String what) {
        return PREFIX + "desc." + what;
    }

    /** Something was done. */
    static void done(@Nonnull CommandContext ctx, @Nonnull String key, @Nonnull Object... args) {
        ctx.sendMessage(Msg.key(PREFIX + key, args).color(GOOD));
    }

    /** Something was refused, and why. */
    static void refused(@Nonnull CommandContext ctx, @Nonnull String key, @Nonnull Object... args) {
        ctx.sendMessage(Msg.key(PREFIX + key, args).color(BAD));
    }

    /** A line already built (a boost's own refusal), as a refusal. */
    static void refusedLine(@Nonnull CommandContext ctx, @Nonnull Message line) {
        ctx.sendMessage(line.color(BAD));
    }
}
