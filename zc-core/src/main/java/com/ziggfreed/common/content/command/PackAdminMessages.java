package com.ziggfreed.common.content.command;

import java.util.Locale;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.content.ServerContent;
import com.ziggfreed.common.i18n.Msg;

/**
 * Where every line this command family says resolves.
 *
 * <p><b>Keys live in {@code ziggfreedcommon.content.admin.lang}</b>, so the in-file key drops the
 * {@code ziggfreedcommon.content.admin.} segment the filename already carries. The DESCRIPTION strings a
 * command is constructed with are keys too: the engine resolves a command description through its own
 * localization module.
 */
final class PackAdminMessages {

    /** The key family every line here resolves under (the shipped file name, minus {@code .lang}). */
    static final String PREFIX = "ziggfreedcommon.content.admin.";

    private PackAdminMessages() {
    }

    /** What a command is FOR, resolved by the engine's own help. */
    @Nonnull
    static String desc(@Nonnull String what) {
        return PREFIX + "desc." + what;
    }

    @Nonnull
    static Message line(@Nonnull String key, @Nonnull Object... args) {
        return Msg.tr(PREFIX, key, args);
    }

    /** The pack's state as a keyed word. */
    @Nonnull
    static Message state(@Nonnull ServerContent.State state) {
        return line("state." + state.name().toLowerCase(Locale.ROOT));
    }
}
