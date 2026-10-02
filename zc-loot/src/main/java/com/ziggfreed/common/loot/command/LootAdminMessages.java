package com.ziggfreed.common.loot.command;

import javax.annotation.Nonnull;

/**
 * Where every line this command family says resolves.
 *
 * <p><b>Keys live in {@code ziggfreedcommon.loot.admin.lang}</b>, so the in-file key drops the
 * {@code ziggfreedcommon.loot.admin.} segment the filename already carries. The DESCRIPTION strings a
 * command is constructed with are keys too: the engine resolves a command description through its
 * own localization module, so a plain English one would render as text nobody translated.
 */
public final class LootAdminMessages {

    /** The key family every line here resolves under (the shipped file name, minus {@code .lang}). */
    public static final String PREFIX = "ziggfreedcommon.loot.admin.";

    private LootAdminMessages() {
    }

    /** What a command is FOR, resolved by the engine's own help. */
    @Nonnull
    public static String desc(@Nonnull String what) {
        return PREFIX + "desc." + what;
    }
}
