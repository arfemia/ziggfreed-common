package com.ziggfreed.common.ui.owner;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;

import com.ziggfreed.common.ui.hud.settings.HudSettingsPages;

/**
 * Registers the owner menu at library setup: {@code /zigadmin}, the library's own section, and this module's owner
 * screen on it (the server's HUD layout). The library's other owner screens join from the wiring root, which sees their
 * modules; every owner form joins on its own. Registration only ({@code RootRegistrationOnlyTest}).
 */
public final class OwnerMenuBootstrap {

    /** The library section's place: first, unless a consumer's command names its own first. */
    public static final int LIBRARY_ORDER = 0;

    private OwnerMenuBootstrap() {
    }

    /** The command, the library's section and the HUD layout tile. Not guarded: a failed registration throws here. */
    public static void install(@Nonnull JavaPlugin plugin) {
        plugin.getCommandRegistry().registerCommand(new ZigAdminCommand());
        OwnerMenu.section(OwnerMenu.LIBRARY, OwnerText.line("menu.section.library"), LIBRARY_ORDER);
        OwnerMenu.add(OwnerTile.screen(OwnerMenu.LIBRARY, "ziggfreedcommon:hud_layout",
                        OwnerText.line("tile.hud.title"),
                        viewer -> HudSettingsPages.open(viewer.store(), viewer.ref(), viewer.player()))
                .order(40)
                .line(OwnerText.line("tile.hud.line"))
                .icon("Deco_Map"));
    }
}
