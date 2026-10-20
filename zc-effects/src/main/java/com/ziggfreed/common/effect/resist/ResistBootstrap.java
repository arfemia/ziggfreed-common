package com.ziggfreed.common.effect.resist;

import javax.annotation.Nonnull;

import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.assetstore.map.IndexedLookupTableAssetMap;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.plugin.PluginBase;

/**
 * Registers the per-type damage resistance ({@link DamageResistReconciler}): the per-tick system that keeps
 * every living player's resistance effects in step with their {@code DamageResist_<Type>} stats, the
 * after-armor step ({@link ResistAfterArmorSystem}: armor penetration, a rolled landing, the 1-point
 * minimum), and the re-write of the effect bodies whenever the damage causes load. Registration only.
 * Nothing to wire for a consumer: a server running the library alone gets the stats' resistance, and a
 * consumer adds only its rules and sources ({@code DamageResistances.installGate}, {@code installCeiling},
 * {@code registerSource}).
 */
public final class ResistBootstrap {

    private ResistBootstrap() {
    }

    /** Call once from plugin {@code setup()}. A failed registration rethrows (R700): every resistance would be gone. */
    public static void install(@Nonnull PluginBase plugin) {
        try {
            plugin.getEntityStoreRegistry().registerSystem(new DamageResistSyncSystem());
            plugin.getEntityStoreRegistry().registerSystem(new ResistAfterArmorSystem());
            plugin.getEventRegistry().register(LoadedAssetsEvent.class, DamageCause.class,
                    (LoadedAssetsEvent<String, DamageCause, IndexedLookupTableAssetMap<String, DamageCause>> ev) ->
                            DamageResistReconciler.onDamageCausesLoaded());
        } catch (Throwable t) {
            throw new IllegalStateException("[resist] could not register the damage resistance pass", t);
        }
    }
}
