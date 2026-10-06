package com.ziggfreed.common.settings.page;

import javax.annotation.Nonnull;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.icon.IconSpec;
import com.ziggfreed.common.ui.menu.MenuEntry;
import com.ziggfreed.common.ui.menu.MenuSlot;
import com.ziggfreed.common.ui.route.Destination;
import com.ziggfreed.common.ui.route.DestinationContext;
import com.ziggfreed.common.ui.route.DestinationType;
import com.ziggfreed.common.ui.route.Destinations;

/**
 * The Settings tab as a destination: {@code "Open": "Settings"} in any file, the menu's Settings slot,
 * {@code /zighud}, and a consumer's old settings link all open the same page on the player's own ref.
 * Registered at setup by {@link SettingsBootstrap}, before any asset decodes.
 */
public final class SettingsDestinations {

    public static final String OWNER = "ziggfreedcommon";
    public static final String TYPE = "Settings";
    public static final Settings SETTINGS = new Settings();

    /** The Settings tab's picture on the rail: a lever, a thing you set. */
    public static final String TAB_ICON = "Deco_Lever";

    private static final String TAB_KEY = "ziggfreedcommon.ui.menu.settings";

    private SettingsDestinations() {
    }

    /** Claim the type in the shared vocabulary. Once, at setup. */
    public static void register() {
        Destinations.register(OWNER, DestinationType.of(TYPE, Settings.class, Settings.CODEC, SettingsDestinations::open));
    }

    /** The menu's Settings tab: every player's, always shown. */
    @Nonnull
    public static MenuEntry entry() {
        return MenuSlot.SETTINGS.entry(Msg.key(TAB_KEY), IconSpec.ofItem(TAB_ICON), SETTINGS, viewer -> true);
    }

    private static boolean open(@Nonnull Settings destination, @Nonnull DestinationContext ctx) {
        return SettingsPages.open(ctx.store(), ctx.playerReference(), ctx.player());
    }

    /** The Settings tab. */
    public static final class Settings extends Destination {

        public static final BuilderCodec<Settings> CODEC = BuilderCodec.builder(Settings.class, Settings::new).build();
    }
}
