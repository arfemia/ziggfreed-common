package com.ziggfreed.common.calendar.command;

import java.awt.Color;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.ziggfreed.common.i18n.Msg;

/**
 * Every line /zigcalendar says: a sentence is a KEY in {@code ziggfreedcommon.calendar.admin.lang},
 * resolved on the reader's own client; an id, a year or a day is a raw argument. The description strings a
 * command and its arguments are built with are keys too: the engine resolves them itself.
 */
public final class CalendarAdminMessages {

    /** The key family every line resolves under (the shipped file name, minus {@code .lang}). */
    public static final String PREFIX = "ziggfreedcommon.calendar.admin.";

    private static final Color HEADING = new Color(0xFFCC66);
    private static final Color DETAIL = new Color(0xAAAAAA);
    private static final Color BAD = new Color(0xFF5555);
    private static final Color GOOD = new Color(0x77DD77);

    private CalendarAdminMessages() {
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

    /** One status line. */
    public static void line(@Nonnull CommandContext ctx, @Nonnull CalendarStatusLines.Line line) {
        ctx.sendMessage(Msg.key(PREFIX + line.key(), line.args().toArray()).color(DETAIL));
    }
}
