package com.ziggfreed.common.effect.resist;

import javax.annotation.Nonnull;

import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.assetstore.map.IndexedLookupTableAssetMap;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.util.SafeLog;

/**
 * Registers the per-type damage resistance ({@link DamageResistReconciler}): the per-tick system that keeps
 * every living player's resistance effects in step with their {@code DamageResist_<Type>} stats, and the
 * re-write of the effect bodies whenever the damage causes load. Registration only. Nothing to wire for a
 * consumer: a server running the library alone gets the stats' resistance, and a consumer adds only its
 * rules ({@code DamageResistances.installGate}, {@code installCeiling}).
 */
public final class ResistBootstrap {

    private ResistBootstrap() {
    }

    /** Call once from plugin {@code setup()}. */
    public static void install(@Nonnull PluginBase plugin) {
        try {
            plugin.getEntityStoreRegistry().registerSystem(new DamageResistSyncSystem());
            plugin.getEventRegistry().register(LoadedAssetsEvent.class, DamageCause.class,
                    (LoadedAssetsEvent<String, DamageCause, IndexedLookupTableAssetMap<String, DamageCause>> ev) ->
                            DamageResistReconciler.onDamageCausesLoaded());
        } catch (Throwable t) {
            SafeLog.warn("[resist] could not register the damage resistance pass, so DamageResist stats resist nothing this boot", t);
        }
    }
}
