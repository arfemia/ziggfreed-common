package com.ziggfreed.common.objectives.title;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.entity.title.ActiveTitles;
import com.ziggfreed.common.objectives.title.page.TitlePickerPages;
import com.ziggfreed.common.settings.page.SettingsRow;
import com.ziggfreed.common.settings.page.SettingsSection;
import com.ziggfreed.common.settings.page.SettingsViewer;
import com.ziggfreed.common.ui.hud.command.HudMessages;
import com.ziggfreed.common.ui.hud.settings.HudSettingsPages;

/**
 * The Settings tab's Title card: a tile naming the title the player shows and opening the picker (whose
 * Back returns here), and, for the HUD page's own audience alone, a tile opening the server's HUD layout.
 */
public final class TitleSettings {

    public static final String ID = "title";
    static final String PICKER = "title.picker";
    static final String LAYOUT = "title.hud_layout";
    static final String PICKER_ICON = "Furniture_Outlander_Banner";
    static final String LAYOUT_ICON = "Deco_Map";

    private TitleSettings() {
    }

    @Nonnull
    public static SettingsSection section() {
        return new SettingsSection(ID, TitleText.picker("settings_heading"), List.of(
                SettingsRow.tile(PICKER, TitleText.picker("settings_tile"), PICKER_ICON, viewer -> true, PICKER_TILE),
                SettingsRow.tile(LAYOUT, HudMessages.line("settings.layout_tile"), LAYOUT_ICON,
                        TitleSettings::administers, LAYOUT_TILE)));
    }

    /** Whether the viewer may open the server's HUD layout: the HUD page's own audience, asked about this player. */
    static boolean administers(@Nonnull SettingsViewer viewer) {
        return viewer.store() != null && viewer.ref() != null && viewer.player() != null
                && HudSettingsPages.mayAdminister(viewer.store(), viewer.ref(), viewer.player());
    }

    /** The title tile's line: the title this player shows, or that they show none (unknown or switched off reads as none). */
    @Nonnull
    static Message worn(@Nullable UUID playerId) {
        String shown = playerId == null ? null : ActiveTitles.of(playerId);
        TitleAsset title = shown == null ? null : TitleConfig.getInstance().shown(shown);
        return title == null ? TitleText.picker("settings_none")
                : TitleText.picker("settings_wearing", TitleText.nameOf(shown, title));
    }

    private static final SettingsRow.Tile PICKER_TILE = new SettingsRow.Tile() {
        @Nonnull
        @Override
        public Message line(@Nonnull SettingsViewer viewer) {
            PlayerRef playerRef = viewer.playerRef();
            return worn(playerRef == null ? null : playerRef.getUuid());
        }

        @Override
        public boolean open(@Nonnull SettingsViewer viewer) {
            return viewer.store() != null && viewer.ref() != null && viewer.player() != null
                    && TitlePickerPages.open(viewer.store(), viewer.ref(), viewer.player());
        }
    };

    private static final SettingsRow.Tile LAYOUT_TILE = new SettingsRow.Tile() {
        @Nonnull
        @Override
        public Message line(@Nonnull SettingsViewer viewer) {
            return HudMessages.line("settings.layout_tile_line");
        }

        @Override
        public boolean open(@Nonnull SettingsViewer viewer) {
            return viewer.store() != null && viewer.ref() != null && viewer.player() != null
                    && HudSettingsPages.open(viewer.store(), viewer.ref(), viewer.player());
        }
    };
}
