package com.ziggfreed.common.settings.page;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.ui.hud.command.HudMessages;
import com.ziggfreed.common.ui.hud.panel.HudSpotAsset;
import com.ziggfreed.common.ui.hud.panel.HudSpotConfig;

/**
 * The entries of a "where it sits" dropdown for one surface: a first entry meaning "no spot of my own"
 * (worded by {@code ziggfreedcommon.hud.settings.<noneKey>}), then every spot the owner offers that
 * surface ({@link HudSpotConfig#offeredFor}), in listing order. ONE list for the Settings tab and the
 * server's HUD layout page.
 */
public final class SpotOptions {

    /** The value of the first entry: no pick of the player's own (or, on the server page, the shipped file's). */
    public static final String SERVER_CHOICE = "";

    private SpotOptions() {
    }

    @Nonnull
    public static List<SettingsOption> of(@Nonnull String surface, @Nonnull String noneKey) {
        List<SettingsOption> out = new ArrayList<>();
        out.add(new SettingsOption(SERVER_CHOICE,
                LocalizableString.fromMessageId(HudMessages.key("settings." + noneKey))));
        for (HudSpotAsset spot : HudSpotConfig.getInstance().offeredFor(surface)) {
            String key = spot.labelKey();
            LocalizableString label = key != null
                    ? LocalizableString.fromMessageId(ContentKeys.resolved(key))
                    : LocalizableString.fromString(spot.getId());
            out.add(new SettingsOption(spot.getId().toLowerCase(Locale.ROOT), label));
        }
        return List.copyOf(out);
    }
}
