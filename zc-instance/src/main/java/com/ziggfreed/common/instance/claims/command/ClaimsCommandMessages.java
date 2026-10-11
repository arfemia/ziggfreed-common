package com.ziggfreed.common.instance.claims.command;

import java.awt.Color;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.instance.claims.ClaimsText;

/**
 * Every line {@code /zigclaims} says: a sentence is a key in {@code ziggfreedcommon.claims.lang} (under
 * {@code command.}), resolved on the reader's own client, and an id, a player's name or a count is an argument.
 * A command's and an argument's DESCRIPTION is a key too, which the engine's own help resolves.
 */
public final class ClaimsCommandMessages {

    /** The key family every line here resolves under. */
    public static final String PREFIX = ClaimsText.PREFIX + "command.";

    private static final Color BAD = new Color(0xFF5555);
    private static final Color GOOD = new Color(0x77DD77);
    private static final Color DETAIL = new Color(0xAAAAAA);

    private ClaimsCommandMessages() {
    }

    /** What a command or one of its arguments is FOR, resolved by the engine's own help. */
    @Nonnull
    public static String desc(@Nonnull String what) {
        return PREFIX + "desc." + what;
    }

    /** Something was done. */
    public static void done(@Nonnull CommandContext ctx, @Nonnull String key, @Nonnull Object... args) {
        ctx.sendMessage(Msg.key(PREFIX + key, args).color(GOOD));
    }

    /** An answer that is detail rather than a success or a refusal. */
    public static void detail(@Nonnull CommandContext ctx, @Nonnull String key, @Nonnull Object... args) {
        ctx.sendMessage(Msg.key(PREFIX + key, args).color(DETAIL));
    }

    /** Something was refused, and why. */
    public static void refused(@Nonnull CommandContext ctx, @Nonnull String key, @Nonnull Object... args) {
        ctx.sendMessage(Msg.key(PREFIX + key, args).color(BAD));
    }
}
