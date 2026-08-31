package com.ziggfreed.common.npc.placement.command;

import java.awt.Color;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.ziggfreed.common.i18n.Msg;

/**
 * Every line this command family says, and the two rules it says them by.
 *
 * <p><b>A sentence is a KEY; an id or a number is a raw argument.</b> The words belong to whoever is
 * reading them, and they are resolved on that person's own client, so nothing here builds a sentence
 * out of English fragments. A placement id, a role id and a coordinate are DATA - they read the same
 * in every language, and a translated one would stop naming the thing an author has to go and edit.
 *
 * <p><b>Keys live in {@code ziggfreedcommon.npc.admin.lang}</b>, so the in-file key drops the
 * {@code ziggfreedcommon.npc.admin.} segment the filename already carries. The DESCRIPTION strings a
 * command and its arguments are constructed with are keys too: the engine resolves a command
 * description through its own localization module, so a plain English one renders to the reader as
 * the raw text nobody translated.
 */
public final class NpcAdminMessages {

    /** The key family every line here resolves under (the shipped file name, minus {@code .lang}). */
    public static final String PREFIX = "ziggfreedcommon.npc.admin.";

    private static final Color HEADING = new Color(0xFFCC66);
    private static final Color DETAIL = new Color(0xAAAAAA);
    private static final Color BAD = new Color(0xFF5555);
    private static final Color GOOD = new Color(0x77DD77);

    private NpcAdminMessages() {
    }

    /** What a command or one of its arguments is FOR, resolved by the engine's own help. */
    @Nonnull
    public static String desc(@Nonnull String what) {
        return PREFIX + "desc." + what;
    }

    /** A heading line: what the rows under it are. */
    public static void heading(@Nonnull CommandContext ctx, @Nonnull String key,
            @Nonnull Object... args) {
        ctx.sendMessage(Msg.key(PREFIX + key, args).color(HEADING));
    }

    /** A row, or anything else that is detail rather than an answer. */
    public static void detail(@Nonnull CommandContext ctx, @Nonnull String key,
            @Nonnull Object... args) {
        ctx.sendMessage(Msg.key(PREFIX + key, args).color(DETAIL));
    }

    /** Something was done. */
    public static void done(@Nonnull CommandContext ctx, @Nonnull String key,
            @Nonnull Object... args) {
        ctx.sendMessage(Msg.key(PREFIX + key, args).color(GOOD));
    }

    /** Something was refused, and why. */
    public static void refused(@Nonnull CommandContext ctx, @Nonnull String key,
            @Nonnull Object... args) {
        ctx.sendMessage(Msg.key(PREFIX + key, args).color(BAD));
    }
}
