package com.ziggfreed.common.instance.dungeon.command;

import java.awt.Color;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.instance.dungeon.DungeonAsset;
import com.ziggfreed.common.instance.dungeon.DungeonRefusals;
import com.ziggfreed.common.season.SeasonGate;

/**
 * Every line {@code /zigdungeon} says: a sentence or a word is a key in {@code ziggfreedcommon.dungeon.lang},
 * resolved on the reader's own client; an id, a world name or a prefab path is a raw argument, a count a typed
 * number. A command's and an argument's DESCRIPTION is a key too, which the engine's own help resolves.
 */
public final class DungeonCommandMessages {

    /** The key family the dungeon words resolve under (the shipped file name, minus {@code .lang}). */
    public static final String PREFIX = "ziggfreedcommon.dungeon.";

    /** The command lines' own keys, under {@link #PREFIX}. */
    static final String COMMAND = PREFIX + "command.";

    private static final Color HEADING = new Color(0xFFCC66);
    private static final Color DETAIL = new Color(0xAAAAAA);
    private static final Color GOOD = new Color(0x77DD77);
    private static final Color BAD = new Color(0xFF5555);

    private DungeonCommandMessages() {
    }

    /** What a command or one of its arguments is FOR, resolved by the engine's own help. */
    @Nonnull
    public static String desc(@Nonnull String what) {
        return COMMAND + "desc." + what;
    }

    /** A word or phrase nested in a line (a flag, a state): a key under {@link #PREFIX}. */
    @Nonnull
    static Message word(@Nonnull String key, @Nonnull Object... args) {
        return Msg.key(PREFIX + key, args);
    }

    /** A dungeon's name as its players read it, or its id when it names no key ({@link DungeonRefusals#dungeonName}). */
    @Nonnull
    static Message dungeonName(@Nonnull DungeonAsset dungeon) {
        return DungeonRefusals.dungeonName(dungeon);
    }

    /** Switched off, waiting on its season, or open. */
    @Nonnull
    static Message state(@Nonnull DungeonAsset dungeon) {
        if (!dungeon.isEnabled()) {
            return word("state.off");
        }
        return SeasonGate.live(dungeon.getSeason()) ? word("state.open") : word("state.out_of_season");
    }

    static void heading(@Nonnull CommandContext ctx, @Nonnull String key, @Nonnull Object... args) {
        ctx.sendMessage(Msg.key(COMMAND + key, args).color(HEADING));
    }

    static void detail(@Nonnull CommandContext ctx, @Nonnull String key, @Nonnull Object... args) {
        ctx.sendMessage(Msg.key(COMMAND + key, args).color(DETAIL));
    }

    static void done(@Nonnull CommandContext ctx, @Nonnull String key, @Nonnull Object... args) {
        ctx.sendMessage(Msg.key(COMMAND + key, args).color(GOOD));
    }

    static void refused(@Nonnull CommandContext ctx, @Nonnull String key, @Nonnull Object... args) {
        ctx.sendMessage(Msg.key(COMMAND + key, args).color(BAD));
    }
}
