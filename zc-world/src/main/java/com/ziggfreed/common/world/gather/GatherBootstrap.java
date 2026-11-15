package com.ziggfreed.common.world.gather;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.match.MatchSets;
import com.ziggfreed.common.util.SafeLog;

/**
 * Wires the gather and build steps at plugin {@code setup()}: block speed, tool care, the break chain, thrift, growth
 * and the gather shapes, and fills zc-core's block family seam ({@code MatchSets.families}). One phase, called once
 * from {@code ZiggfreedCommonPlugin.setup()}, which stays the one authority on call order. Their stores (block families, gather shapes) are registered with the other framework
 * stores. Each part registers on its own, so one that fails costs only itself, and each warning names what a server
 * then goes without.
 */
public final class GatherBootstrap {

    private GatherBootstrap() {
    }

    /** Register the gather and build systems. */
    public static void install(@Nonnull PluginBase plugin) {
        // The shared matcher's BlockFamily: key (zc-core's MatchSets seam) reads the families this module folds, so a
        // track source or bonus row naming a family works on any server running the library, and lists them, so an
        // in-game editor offers the families and says which hold a block.
        BlockFamilyConfig families = BlockFamilyConfig.getInstance();
        MatchSets.families(MatchSets.reader((block, family) -> families.holds(family, block),
                family -> families.resolve(family) != null, families::generation, families::displayFamiliesOf,
                families::displayIds));
        try {
            plugin.getEntityStoreRegistry().registerSystem(new GatherSpeedSystem());
            plugin.getEntityStoreRegistry().registerSystem(new GatherChainSystem());
        } catch (Throwable t) {
            SafeLog.warn("[gather] the gather speed step could not be registered: no GatherSpeed stat speeds a block"
                    + " this boot", t);
        }
        try {
            // A player's break chain and recent placements are forgotten when they leave.
            plugin.getEventRegistry().register(PlayerDisconnectEvent.class, GatherChains::onDisconnect);
            plugin.getEventRegistry().register(PlayerDisconnectEvent.class, RecentPlacements::onDisconnect);
        } catch (Throwable t) {
            SafeLog.warn("[gather] the leave-time clear could not be registered: a departed player's break chain and"
                    + " placements stay in memory until the sweep this boot", t);
        }
        try {
            plugin.getEntityStoreRegistry().registerSystem(new GatherCareSystem());
        } catch (Throwable t) {
            SafeLog.warn("[gather] the tool care step could not be registered: GatherCare spares no wear this boot", t);
        }
        try {
            // The spared block's bare break first: without it a spared block would hand its item back on a break.
            plugin.getEntityStoreRegistry().registerSystem(new ThriftDropSystem());
            plugin.getEntityStoreRegistry().registerSystem(new ThriftDropSystem.Blast());
            plugin.getEntityStoreRegistry().registerSystem(new ThriftDropSystem.EnvironmentBreak());
            plugin.getEntityStoreRegistry().registerSystem(new ThriftSystem());
        } catch (Throwable t) {
            SafeLog.warn("[gather] the thrift step could not be registered: BuildThrift spares no block this boot", t);
        }
        try {
            plugin.getEntityStoreRegistry().registerSystem(new GatherShapeSystem());
            plugin.getEntityStoreRegistry().registerSystem(new HarvestShapeSystem());
        } catch (Throwable t) {
            SafeLog.warn("[gather] the gather shapes could not be registered: no shape runs this boot", t);
        }
        try {
            GrowthSpeedSystem growth = GrowthSpeedSystem.create();
            if (growth == null) {
                SafeLog.warn("[gather] the engine's farming components are not registered, so GrowthSpeed grows"
                        + " nothing this boot");
                return;
            }
            plugin.getChunkStoreRegistry().registerSystem(growth);
        } catch (Throwable t) {
            SafeLog.warn("[gather] the growth step could not be registered: GrowthSpeed grows nothing this boot", t);
        }
    }
}
