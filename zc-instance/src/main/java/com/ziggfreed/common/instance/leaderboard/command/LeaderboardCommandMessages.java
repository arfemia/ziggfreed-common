package com.ziggfreed.common.instance.leaderboard.command;

import java.awt.Color;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.instance.leaderboard.EncounterLeaderboardMessages;

/**
 * Every line {@code /zigleaderboard} says, and the rule it says them by: a sentence is a key in
 * {@code ziggfreedcommon.leaderboard.lang} (under {@code command.}), resolved on the reader's own
 * client, and an id or a fight's name is an argument. The DESCRIPTION strings a command and its
 * arguments are built with are keys too: the engine resolves a command's description through its own
 * localization, so a plain English one would reach every reader untranslated.
 */
public final class LeaderboardCommandMessages {

    /** The key family every line here resolves under. */
    public static final String PREFIX = EncounterLeaderboardMessages.PREFIX + "command.";

    private static final Color HEADING = new Color(0xFFCC66);
    private static final Color DETAIL = new Color(0xAAAAAA);
    private static final Color BAD = new Color(0xFF5555);

    private LeaderboardCommandMessages() {
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

    /** Something was refused, and this is why. */
    public static void refused(@Nonnull CommandContext ctx, @Nonnull String key, @Nonnull Object... args) {
        ctx.sendMessage(Msg.key(PREFIX + key, args).color(BAD));
    }
}
