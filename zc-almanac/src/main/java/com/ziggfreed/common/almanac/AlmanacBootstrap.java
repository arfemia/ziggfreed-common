package com.ziggfreed.common.almanac;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.almanac.asset.AlmanacOwnerLayers;
import com.ziggfreed.common.almanac.command.ZigAlmanacCommand;
import com.ziggfreed.common.almanac.page.AlmanacDestinations;
import com.ziggfreed.common.almanac.page.AlmanacMenuTab;
import com.ziggfreed.common.almanac.stats.AlmanacStatistics;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.ui.kit.LedgerContributions;
import com.ziggfreed.common.ui.menu.MenuSlot;
import com.ziggfreed.common.ui.menu.ZigMenu;
import com.ziggfreed.common.util.SafeLog;

/**
 * Registers the Almanac at library setup, called once from the wiring root's {@code setup()}: the
 * per-player record and its connect hook (before any world loads), the server's own totals and their
 * file, the owner's switch, the feature, the destination, the moment counter, the book's Seasons
 * statistics and the {@code /zigalmanac} family. The page store itself is registered with the other framework stores. Registration only
 * ({@code RootRegistrationOnlyTest}). Its {@link #shutdown} is called from the wiring root's
 * {@code shutdown()} and writes the server's totals one last time.
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
        // Beside the owner files; the 2.2.0 builds before its release kept the totals in the library's
        // data folder, and the first load that finds them only there moves them across.
        ServerTallies.shared().init(AlmanacOwnerLayers.directory(), plugin.getDataDirectory());
        AlmanacOwnerLayers.readSwitch();
        registerVocabulary();
        try {
            plugin.getCommandRegistry().registerCommand(new ZigAlmanacCommand());
        } catch (Throwable t) {
            SafeLog.warn("[almanac] the /zigalmanac command could not be registered", t);
        }
    }

    /**
     * Write the server's totals still waiting on their debounce, so the counts of the last seconds before
     * a stop are kept. Called from the library's {@code shutdown()}, which the server runs after it has
     * disconnected every player and shut every world down, so nothing counts after it. A stop with nothing
     * new writes nothing, and it never throws.
     */
    public static void shutdown() {
        try {
            ServerTallies.shared().flushNow();
        } catch (Throwable t) {
            SafeLog.warn("[almanac] the server totals could not be written at shutdown", t);
        }
    }

    /**
     * The plugin-free half: the feature (before the first progression publish), the destination and
     * the menu tab (before any asset decodes), the counter, at the library-default rank, the book's
     * Seasons statistics (read on every look, so it needs nothing loaded yet), and the keepsake check, run
     * once when the shared progression runtime is built (both stores have loaded by then).
     */
    public static void registerVocabulary() {
        AlmanacSwitch.registerFeature();
        AlmanacDestinations.register();
        ZigMenu.fill(MenuSlot.ALMANAC, AlmanacMenuTab.entry());
        ProgressionRuntime.defaults(OWNER).momentListener(new AlmanacMomentListener(
                OccurrenceAlmanacCalendar.INSTANCE, ServerTallies.shared()));
        LedgerContributions.contribute(LedgerContributions.STATISTICS, AlmanacStatistics.production());
        ProgressionRuntime.onBuilt(AlmanacKeepsakeCheck::logFindings);
    }
}
