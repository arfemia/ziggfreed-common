package com.ziggfreed.common.reputation;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.event.events.BootEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.entity.EntityBootstrap;
import com.ziggfreed.common.loot.reward.RewardChips;
import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.reputation.asset.ReputationOwnerLayers;
import com.ziggfreed.common.reputation.page.ReputationDestinations;
import com.ziggfreed.common.reputation.page.ReputationMenuTab;
import com.ziggfreed.common.ui.menu.MenuSlot;
import com.ziggfreed.common.ui.menu.ZigMenu;
import com.ziggfreed.common.util.SafeLog;

/**
 * Registers the reputation module at library setup, called once from the wiring root's {@code setup()}
 * (after the equip bridge is installed): the owner's switch, the three readings, the Reputation reward kind
 * and how it reads, the kill table on the shared moment stream, the Reputation destination and its tab in
 * the shared menu, the effective-rank checks at login, on every equip and their memory dropped at
 * disconnect, and the once-at-boot log of broken reputation files.
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
        registerVocabulary();
        registerMenu();
        registerRankChecks(plugin);
        registerAudit(plugin);
    }

    /** The plugin-free half: the readings, the reward kind and its chip, and the kill table. */
    public static void registerVocabulary() {
        try {
            ReputationService service = ReputationRuntime.service();
            ReputationFactors.contribute(service);
            ReputationRewardKind.registerInto(RewardKinds.shared(), service);
            RewardChips.contribute(ReputationRewardKind.chips(service));
            ProgressionRuntime.defaults(OWNER).momentListener(
                    new ReputationKillListener(service, ReputationKillListener.WORLD_THREAD));
        } catch (Throwable t) {
            SafeLog.warn("[reputation] could not declare the reputation readings, reward kind and kill table", t);
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
            ReputationRankTriggers.hangOnBridge(EntityBootstrap.equipStatBridge());
        } catch (Throwable t) {
            SafeLog.warn("[reputation] could not hang the reputation rank checks", t);
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
