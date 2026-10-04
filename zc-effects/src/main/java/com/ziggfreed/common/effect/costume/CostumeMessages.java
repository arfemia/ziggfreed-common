package com.ziggfreed.common.effect.costume;

import java.awt.Color;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.ziggfreed.common.i18n.Msg;

/**
 * Every line the costume says, to the player dressed and at the command line. A sentence is a key
 * the reader's own client resolves; a player's name is an argument.
 *
 * <p><b>Keys live in {@code ziggfreedcommon.costume.lang}</b>, so the in-file key drops the
 * {@code ziggfreedcommon.costume.} segment the file name carries. A command's description is a key
 * too: the engine resolves it through its own localization.
 */
public final class CostumeMessages {

    /** The key family every line here resolves under: the shipped file's name, minus {@code lang}. */
    public static final String PREFIX = "ziggfreedcommon.costume.";

    private static final Color DETAIL = new Color(0xAAAAAA);
    private static final Color BAD = new Color(0xFF5555);
    private static final Color GOOD = new Color(0x77DD77);

    private CostumeMessages() {
    }

    /** What a command or one of its arguments is for, as the key the engine's help resolves. */
    @Nonnull
    public static String desc(@Nonnull String what) {
        return PREFIX + "desc." + what;
    }

    /** A line of this family as a client-resolved message. */
    @Nonnull
    public static Message line(@Nonnull String key, @Nonnull Object... args) {
        return Msg.key(PREFIX + key, args);
    }

    /** The key a dressed player reads: naming who dressed them when a player did. */
    @Nonnull
    static String dressedKey(@Nullable String dresserName) {
        return dresserName == null || dresserName.isBlank() ? "notice.dressed" : "notice.dressed_by";
    }

    /** What a player just put in a costume reads, with how to take it off. */
    @Nonnull
    public static Message dressed(@Nullable String dresserName) {
        String key = dressedKey(dresserName);
        return dresserName == null || dresserName.isBlank() ? line(key) : line(key, dresserName.trim());
    }

    /** Something was done. */
    static void done(@Nonnull CommandContext ctx, @Nonnull String key, @Nonnull Object... args) {
        ctx.sendMessage(line(key, args).color(GOOD));
    }

    /** Nothing needed doing. */
    static void detail(@Nonnull CommandContext ctx, @Nonnull String key, @Nonnull Object... args) {
        ctx.sendMessage(line(key, args).color(DETAIL));
    }

    /** Something was refused, and this is why. */
    static void refused(@Nonnull CommandContext ctx, @Nonnull String key, @Nonnull Object... args) {
        ctx.sendMessage(line(key, args).color(BAD));
    }
}
