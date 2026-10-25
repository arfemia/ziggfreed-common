package com.ziggfreed.common.reputation;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.event.events.BootEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.counter.NativeReaders;
import com.ziggfreed.common.loot.reward.RewardChips;
import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.playerdata.ZigPlayerRecordsRestoredEvent;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.reputation.asset.ReputationOwnerLayers;
import com.ziggfreed.common.reputation.page.ReputationDestinations;
import com.ziggfreed.common.reputation.page.ReputationMenuTab;
import com.ziggfreed.common.track.event.ZigTrackChangedEvent;
import com.ziggfreed.common.ui.menu.MenuSlot;
import com.ziggfreed.common.ui.menu.ZigMenu;
import com.ziggfreed.common.util.SafeLog;

/**
 * Registers the reputation module at library setup, called once from the wiring root's {@code setup()}:
 * the owner's switch, the per-player record and the connect hook that attaches it, the two readings, the
 * Reputation reward kind
 * and how it reads, the kill table on the shared moment stream, the standing reader the stat board ranks,
 * the Reputation destination and its tab in
 * the shared menu, the rank check at login and its memory dropped at
 * disconnect, the reputations' tracks built at boot and their outside moves followed, and the once-at-boot
 * log of broken reputation files.
 * The companion store is registered with the other framework stores. Registration only
 * ({@code RootRegistrationOnlyTest}).
 */
public final class ReputationBootstrap {

    /** The owner every registration here is attributed to: the library itself. */
    public static final String OWNER = "ziggfreedcommon";

    private ReputationBootstrap() {
    }

    /** Everything the module registers, in order. */
    public static void install(@Nonnull PluginBase plugin) {
        try {
            ReputationOwnerLayers.readSwitch();
        } catch (Throwable t) {
            SafeLog.warn("[reputation] the owner's reputation switch could not be read", t);
        }
        // The per-player record (the Beyond payouts waiting to be collected) and its connect hook, before any
        // world loads.
        ReputationComponent.register(plugin.getEntityStoreRegistry());
        ReputationComponent.install(plugin);
        registerVocabulary();
        registerMenu();
        registerRankChecks(plugin);
        registerTracks(plugin);
        registerAudit(plugin);
    }

    /**
     * The plugin-free half: the readings, the reward kind and its chip, the kill table, and the standing
     * reader whoever ranks native stats asks (zc-core's {@code NativeReaders}, so this module needs no edge to
     * the stat store).
     */
    public static void registerVocabulary() {
        try {
            ReputationService service = ReputationRuntime.service();
            ReputationFactors.contribute(service);
            ReputationRewardKind.registerInto(RewardKinds.shared(), service);
            RewardChips.contribute(ReputationRewardKind.chips(service));
            ProgressionRuntime.defaults(OWNER).momentListener(
                    new ReputationKillListener(service, ReputationKillListener.WORLD_THREAD));
            NativeReaders.register(OWNER, ReputationStandingReader.STAT_ID, new ReputationStandingReader(service));
        } catch (Throwable t) {
            SafeLog.warn("[reputation] could not declare the reputation readings, reward kind, kill table and"
                    + " standing reader", t);
        }
    }

    /** The Reputation destination (before any asset decodes) and its tab in the shared menu. */
    public static void registerMenu() {
        try {
            ReputationDestinations.register();
            ZigMenu.fill(MenuSlot.REPUTATION, ReputationMenuTab.entry());
        } catch (Throwable t) {
            SafeLog.warn("[reputation] the Reputation destination and menu tab could not be registered", t);
        }
    }

    private static void registerRankChecks(@Nonnull PluginBase plugin) {
        try {
            plugin.getEventRegistry().registerGlobal(PlayerReadyEvent.class, ReputationRankTriggers::onPlayerReady);
            plugin.getEventRegistry().register(PlayerDisconnectEvent.class, ReputationRankTriggers::onPlayerDisconnect);
        } catch (Throwable t) {
            SafeLog.warn("[reputation] could not hang the reputation rank checks", t);
        }
    }

    /**
     * Each reputation as a track: its track and curve built at boot (and after every companion fold, from the
     * root's store wiring), the per-world warning, every move of a reputation's track made outside the
     * service followed (the engine's copy kept in step, the move told), and climbs a consumer's own store put in place
     * marking the engine's copies behind them.
     */
    private static void registerTracks(@Nonnull PluginBase plugin) {
        try {
            plugin.getEventRegistry().register(BootEvent.class, ReputationTracks::onBoot);
            plugin.getEventRegistry().registerGlobal(ZigTrackChangedEvent.class, ReputationTracks::onTrackChanged);
            plugin.getEventRegistry().registerGlobal(ZigPlayerRecordsRestoredEvent.class,
                    ReputationTracks::onRecordsRestored);
        } catch (Throwable t) {
            SafeLog.warn("[reputation] could not hang the reputation tracks' hooks", t);
        }
    }

    private static void registerAudit(@Nonnull PluginBase plugin) {
        try {
            plugin.getEventRegistry().register(BootEvent.class, event -> ReputationValidator.logErrorsOnce());
        } catch (Throwable t) {
            SafeLog.warn("[reputation] could not register the boot-time reputation audit", t);
        }
    }
}
