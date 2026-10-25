package com.ziggfreed.common.effect.outgoing;

import javax.annotation.Nonnull;

import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.assetstore.map.IndexedLookupTableAssetMap;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.effect.sync.StatEffectSync;

/**
 * Registers the per-type damage bonus on native outgoing damage ({@link DamageBonusReconciler}): its channel on the
 * one stat-to-effect pass, which keeps every living carrier's outgoing bonus effects in step with its per-type
 * damage bonus sources, and the re-write of the effect bodies whenever the damage causes load.
 * Registration only. Nothing to wire for a consumer: a server running the library alone gets the stats'
 * bonus, and a consumer adds only its sources and rules ({@code TypedDamageBonuses.registerSource},
 * {@code installAttackerGate}).
 */
public final class OutgoingBootstrap {

    private OutgoingBootstrap() {
    }

    /** Call once from plugin {@code setup()}. A failed registration rethrows (R700): every bonus would be gone. */
    public static void install(@Nonnull PluginBase plugin) {
        try {
            StatEffectSync.register(DamageBonusReconciler.CHANNEL);
            plugin.getEventRegistry().register(LoadedAssetsEvent.class, DamageCause.class,
                    (LoadedAssetsEvent<String, DamageCause, IndexedLookupTableAssetMap<String, DamageCause>> ev) ->
                            DamageBonusReconciler.onDamageCausesLoaded());
        } catch (Throwable t) {
            throw new IllegalStateException("[damage-bonus] could not register the damage bonus pass", t);
        }
    }
}
