package com.ziggfreed.common.effect.warcry;

import javax.annotation.Nonnull;

import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.assetstore.event.RemovedAssetsEvent;
import com.hypixel.hytale.assetstore.map.IndexedLookupTableAssetMap;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.util.SafeLog;

/**
 * Registers the war cries ({@link WarCries}): the system that runs them and the marks that keep their table in step
 * with effect loads. Registration only. A creature wears a war cry through any effect tagged {@code ZigWarCry}, so a
 * pack's omen, a mob-scaling affix or a role's own effects need no Java.
 */
public final class WarCryBootstrap {

    private WarCryBootstrap() {
    }

    /** Call once from plugin {@code setup()}. Guarded: without it a war-cry effect only does what its file says. */
    public static void install(@Nonnull PluginBase plugin) {
        try {
            plugin.getEntityStoreRegistry().registerSystem(new WarCrySystem());
            plugin.getEventRegistry().register(LoadedAssetsEvent.class, EntityEffect.class,
                    (LoadedAssetsEvent<String, EntityEffect, IndexedLookupTableAssetMap<String, EntityEffect>> ev) ->
                            WarCries.markDirty());
            plugin.getEventRegistry().register(RemovedAssetsEvent.class, EntityEffect.class,
                    (RemovedAssetsEvent<String, EntityEffect, IndexedLookupTableAssetMap<String, EntityEffect>> ev) ->
                            WarCries.markDirty());
        } catch (Throwable t) {
            SafeLog.warn("[warcry] the war cries could not be registered: a war-cry effect rallies nobody this boot", t);
        }
    }
}
