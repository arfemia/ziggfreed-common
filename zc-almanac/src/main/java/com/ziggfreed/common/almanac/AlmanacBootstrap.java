package com.ziggfreed.common.almanac;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.almanac.asset.AlmanacOwnerLayers;
import com.ziggfreed.common.almanac.command.ZigAlmanacCommand;
import com.ziggfreed.common.almanac.page.AlmanacDestinations;
import com.ziggfreed.common.almanac.page.AlmanacMenuTab;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.ui.menu.MenuSlot;
import com.ziggfreed.common.ui.menu.ZigMenu;
import com.ziggfreed.common.util.SafeLog;

/**
 * Registers the Almanac at library setup, called once from the wiring root's {@code setup()}: the
 * per-player record and its connect hook (before any world loads), the server's own totals and their
 * file, the owner's switch, the feature, the destination, the moment counter and the {@code /zigalmanac}
 * family. The page store itself is registered with the other framework stores. Registration only
 * ({@code RootRegistrationOnlyTest}).
 */
public final class AlmanacBootstrap {

    /** The owner every registration here is attributed to: the library itself. */
    public static final String OWNER = "ziggfreedcommon";

    private AlmanacBootstrap() {
    }

    /** Everything the Almanac registers, in order. */
    public static void install(@Nonnull PluginBase plugin) {
        AlmanacComponent.register(plugin.getEntityStoreRegistry());
        AlmanacComponent.install(plugin);
        ServerTallies.shared().init(plugin.getDataDirectory());
        AlmanacOwnerLayers.readSwitch();
        registerVocabulary();
        try {
            plugin.getCommandRegistry().registerCommand(new ZigAlmanacCommand());
        } catch (Throwable t) {
            SafeLog.warn("[almanac] the /zigalmanac command could not be registered", t);
        }
    }

    /**
     * The plugin-free half: the feature (before the first progression publish), the destination and
     * the menu tab (before any asset decodes) and the counter, at the library-default rank.
     */
    public static void registerVocabulary() {
        AlmanacSwitch.registerFeature();
        AlmanacDestinations.register();
        ZigMenu.fill(MenuSlot.ALMANAC, AlmanacMenuTab.entry());
        ProgressionRuntime.defaults(OWNER).momentListener(new AlmanacMomentListener(
                OccurrenceAlmanacCalendar.INSTANCE, ServerTallies.shared()));
    }
}
