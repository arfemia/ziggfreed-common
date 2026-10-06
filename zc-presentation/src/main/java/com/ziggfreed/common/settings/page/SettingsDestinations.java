package com.ziggfreed.common.settings.page;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.ui.route.Destination;

/**
 * The Settings tab as a destination: {@code "Open": "Settings"} in any file, the menu's Settings slot,
 * {@code /zighud}, and a consumer's old settings link all open the same page on the player's own ref.
 * {@code SettingsBootstrap} registers it at setup.
 */
public final class SettingsDestinations {

    public static final String OWNER = "ziggfreedcommon";
    public static final String TYPE = "Settings";
    public static final Settings SETTINGS = new Settings();

    private SettingsDestinations() {
    }

    /** The Settings tab. */
    public static final class Settings extends Destination {

        public static final BuilderCodec<Settings> CODEC = BuilderCodec.builder(Settings.class, Settings::new).build();
    }
}
