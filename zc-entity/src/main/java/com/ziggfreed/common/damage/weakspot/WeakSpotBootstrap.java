package com.ziggfreed.common.damage.weakspot;

import javax.annotation.Nonnull;

import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.assetstore.event.RemovedAssetsEvent;
import com.hypixel.hytale.assetstore.map.IndexedLookupTableAssetMap;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.modules.entity.condition.Condition;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.entity.ZigGroundedCondition;

/**
 * Registers weak spots ({@link WeakSpots}) at plugin {@code setup()}: the hit step ({@link WeakSpotSystem}), the
 * {@code ZigGrounded} entity condition that takes {@code Zig_Airborne} off on landing (into the engine's own
 * condition map, R700), and the marks that keep the window table and vanilla's headshot and backstab index in step
 * with effect and interaction loads. Registration only. Nothing to wire for a consumer: a server running the library
 * alone gets every rule, and a consumer raises the weak-spot stats, registers a source of its own
 * ({@link WeakSpots#registerSource}) and tags its own effects. The settings store is the root's, with every framework
 * store. A failed registration rethrows (R700): weak spots would be gone.
 */
public final class WeakSpotBootstrap {

    private WeakSpotBootstrap() {
    }

    /** Call once from plugin {@code setup()}, before any asset decodes, after the use gate and the route step. */
    public static void install(@Nonnull PluginBase plugin) {
        try {
            plugin.getCodecRegistry(Condition.CODEC).register(ZigGroundedCondition.TYPE_NAME,
                    ZigGroundedCondition.class, ZigGroundedCondition.codec());
            plugin.getEntityStoreRegistry().registerSystem(new WeakSpotSystem());
            plugin.getEventRegistry().register(LoadedAssetsEvent.class, Interaction.class,
                    (LoadedAssetsEvent<String, Interaction, IndexedLookupTableAssetMap<String, Interaction>> ev) ->
                            VanillaWeakSpots.markDirty());
            plugin.getEventRegistry().register(RemovedAssetsEvent.class, Interaction.class,
                    (RemovedAssetsEvent<String, Interaction, IndexedLookupTableAssetMap<String, Interaction>> ev) ->
                            VanillaWeakSpots.markDirty());
            plugin.getEventRegistry().register(LoadedAssetsEvent.class, EntityEffect.class,
                    (LoadedAssetsEvent<String, EntityEffect, IndexedLookupTableAssetMap<String, EntityEffect>> ev) ->
                            WeakSpotWindows.effectsLoaded(ev.getLoadedAssets()));
            plugin.getEventRegistry().register(RemovedAssetsEvent.class, EntityEffect.class,
                    (RemovedAssetsEvent<String, EntityEffect, IndexedLookupTableAssetMap<String, EntityEffect>> ev) ->
                            WeakSpotWindows.effectsRemoved(ev.getRemovedAssets()));
        } catch (Throwable t) {
            throw new IllegalStateException("[weakspot] could not register weak spots", t);
        }
    }
}
