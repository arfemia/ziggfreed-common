package com.ziggfreed.common.ui.menu;

import java.awt.Color;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.ziggfreed.common.i18n.Msg;

/**
 * The words the shared menu's own command says, in {@code ziggfreedcommon.ui.lang} (the menu is a widget
 * the library ships whole). A command description is a key too: the engine resolves it through its own
 * localization, so plain English would reach every reader untranslated.
 */
public final class MenuText {

    public static final String PREFIX = "ziggfreedcommon.ui.";

    /** Said from the console: a menu needs a screen. */
    public static final String NEEDS_PLAYER = "menu.open.needsPlayer";

    /** Said when no tab shows for this player and nothing else lands. */
    public static final String NOTHING = "menu.open.nothing";

    private static final Color BAD = new Color(0xFF5555);

    private MenuText() {
    }

    /** The command's description key. */
    @Nonnull
    public static String desc() {
        return PREFIX + "menu.desc";
    }

    @Nonnull
    public static Message line(@Nonnull String key, @Nonnull Object... args) {
        return Msg.key(PREFIX + key, args);
    }

    public static void refused(@Nonnull CommandContext ctx, @Nonnull String key) {
        ctx.sendMessage(line(key).color(BAD));
    }
}
