package com.ziggfreed.common.encounter.command;

import java.awt.Color;
import java.util.List;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.ziggfreed.common.command.FindingsReply;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.validation.Finding;

/**
 * Every line this command family says, and the two rules it says them by.
 *
 * <p><b>A sentence is a KEY; an id or a number is a raw argument.</b> The words belong to whoever is
 * reading them, resolved on their own client, so nothing here builds a sentence out of English
 * fragments; an asset id, a run id and a count are DATA and read the same in every language.
 *
 * <p><b>Keys live in {@code ziggfreedcommon.encounter.lang}</b> under its {@code admin.} family, so
 * the in-file key is {@code admin.<key>} and resolves as {@code ziggfreedcommon.encounter.admin.<key>}.
 * The DESCRIPTION strings a command and its arguments are constructed with are keys too.
 */
public final class EncounterAdminMessages {

    /** The key family every line here resolves under. */
    public static final String PREFIX = "ziggfreedcommon.encounter.admin.";

    private static final Color HEADING = new Color(0xFFCC66);
    private static final Color DETAIL = new Color(0xAAAAAA);
    private static final Color BAD = new Color(0xFF5555);
    private static final Color GOOD = new Color(0x77DD77);

    private EncounterAdminMessages() {
    }

    /** What a command or one of its arguments is FOR, resolved by the engine's own help. */
    @Nonnull
    public static String desc(@Nonnull String what) {
        return PREFIX + "desc." + what;
    }

    public static void heading(@Nonnull CommandContext ctx, @Nonnull String key, @Nonnull Object... args) {
        ctx.sendMessage(Msg.key(PREFIX + key, args).color(HEADING));
    }

    public static void detail(@Nonnull CommandContext ctx, @Nonnull String key, @Nonnull Object... args) {
        ctx.sendMessage(Msg.key(PREFIX + key, args).color(DETAIL));
    }

    public static void done(@Nonnull CommandContext ctx, @Nonnull String key, @Nonnull Object... args) {
        ctx.sendMessage(Msg.key(PREFIX + key, args).color(GOOD));
    }

    public static void refused(@Nonnull CommandContext ctx, @Nonnull String key, @Nonnull Object... args) {
        ctx.sendMessage(Msg.key(PREFIX + key, args).color(BAD));
    }

    /**
     * Report an audit: the counts first, then each finding, then how many were left out. The one
     * reply every family's validate verb shares ({@link FindingsReply}), under this family's keys.
     */
    public static void findings(@Nonnull CommandContext ctx, @Nonnull List<Finding> findings) {
        FindingsReply.send(ctx, PREFIX, findings);
    }
}
