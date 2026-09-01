package com.ziggfreed.common.world.stash;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.util.SafeLog;

/**
 * Wires the per-block stash store at plugin {@code setup()}. One phase, called once from
 * {@code ZiggfreedCommonPlugin.setup()}, which stays the one authority on call ORDER.
 */
public final class BlockStashBootstrap {

    private BlockStashBootstrap() {
    }

    /**
     * Register the stash chunk component ({@link BlockStashes#REGISTRY_ID}). It registers at
     * {@code setup()} because a chunk-store component must exist before any world loads, or a
     * saved stash would come back as an unknown component instead of data. The store is the
     * library's rather than a consumer's so every mod reading "what is this block holding?" gets
     * the one answer; registration inside {@link BlockStashes#register} already degrades a failure
     * to "no stash was ever stored", and the guard here catches the registry proxy itself being
     * unavailable.
     */
    public static void registerBlockStash(@Nonnull PluginBase plugin) {
        try {
            BlockStashes.register(plugin.getChunkStoreRegistry());
        } catch (Throwable t) {
            SafeLog.warn("[stash] the block-stash chunk component could not be registered: nothing"
                    + " will be stored or read back this boot", t);
        }
    }
}
