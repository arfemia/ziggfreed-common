package com.ziggfreed.common.settings.page;

import java.util.List;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.settings.PlayerSettings;
import com.ziggfreed.common.ui.hud.panel.HudSpotConfig;

/**
 * The two rows every surface a player can move or hide gets (a bar display, the quest tracker): Show and
 * where it sits, read and written through {@link PlayerSettings}. ONE pair for every module's section.
 */
public final class SurfaceRows {

    /** The Where row's first entry's words: the server's own choice. */
    static final String SERVER_SPOT_KEY = "server_spot";

    private SurfaceRows() {
    }

    /** The Show switch of {@code surface}: the player's effective answer, kept as their own choice. */
    @Nonnull
    public static SettingsRow.Toggle show(@Nonnull String surface) {
        return new SettingsRow.Toggle() {
            @Override
            public boolean on(@Nonnull SettingsViewer viewer) {
                return PlayerSettings.shown(viewer.playerRef(), surface);
            }

            @Override
            public boolean set(@Nonnull SettingsViewer viewer, boolean on) {
                PlayerRef playerRef = viewer.playerRef();
                if (!PlayerSettings.writable(playerRef)) {
                    return false;
                }
                PlayerSettings.setShown(playerRef, surface, on);
                return true;
            }
        };
    }

    /** The Where dropdown of {@code surface}: the server's choice first, then each spot offered to it. */
    @Nonnull
    public static SettingsRow.Choice where(@Nonnull String surface) {
        return new SettingsRow.Choice() {
            @Nonnull
            @Override
            public List<SettingsOption> options(@Nonnull SettingsViewer viewer) {
                return SpotOptions.of(surface, SERVER_SPOT_KEY);
            }

            @Nonnull
            @Override
            public String value(@Nonnull SettingsViewer viewer) {
                String pick = PlayerSettings.spot(viewer.playerRef(), surface);
                return pick == null ? SpotOptions.SERVER_CHOICE : pick;
            }

            @Override
            public boolean set(@Nonnull SettingsViewer viewer, @Nonnull String value) {
                PlayerRef playerRef = viewer.playerRef();
                if (!PlayerSettings.writable(playerRef)) {
                    return false;
                }
                PlayerSettings.setSpot(playerRef, surface, value.isBlank() ? null : value.trim());
                return true;
            }
        };
    }

    /** Whether the owner offers {@code surface} any spot beyond the server's own. */
    public static boolean whereOffered(@Nonnull String surface) {
        return !HudSpotConfig.getInstance().offeredFor(surface).isEmpty();
    }
}
