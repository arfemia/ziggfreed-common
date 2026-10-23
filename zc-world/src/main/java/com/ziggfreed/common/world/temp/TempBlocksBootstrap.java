package com.ziggfreed.common.world.temp;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.world.record.BlockRecordSection;

/**
 * Wires temporary blocks at plugin {@code setup()}: the record component every temporary block's chunk section
 * carries ({@link TempBlocks#REGISTRY_ID}), registered before any world loads so a saved record comes back as data,
 * the crumble sweep over the sections holding one ({@link TempBlockSystem}) and the placement step that forgets a
 * record whose block is gone ({@link TempBlockPlaceSystem}). One phase, called once from
 * {@code ZiggfreedCommonPlugin.setup()}, which stays the one authority on call order.
 */
public final class TempBlocksBootstrap {

    private TempBlocksBootstrap() {
    }

    /** Register the temporary-block record component and its crumble sweep. */
    public static void install(@Nonnull PluginBase plugin) {
        try {
            TempBlocks.register(plugin.getChunkStoreRegistry());
            ComponentType<ChunkStore, BlockRecordSection<TempBlock>> type = TempBlocks.componentType();
            if (type == null) {
                SafeLog.warn("[temp] the temporary-block record could not be registered: no temporary block is placed"
                        + " this boot");
                return;
            }
            plugin.getChunkStoreRegistry().registerSystem(new TempBlockSystem(type));
            plugin.getEntityStoreRegistry().registerSystem(new TempBlockPlaceSystem());
        } catch (Throwable t) {
            SafeLog.warn("[temp] temporary blocks could not be registered: no temporary block is placed or crumbles"
                    + " this boot", t);
        }
    }
}
