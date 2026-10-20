package com.ziggfreed.common.stats.condition;

import javax.annotation.Nonnull;

import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.assetstore.event.RemovedAssetsEvent;
import com.hypixel.hytale.assetstore.map.IndexedLookupTableAssetMap;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.modules.entity.condition.Condition;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.health.HealthUtil;
import com.ziggfreed.common.stats.damage.TypedDamageBonuses;

/**
 * Registers conditional stats ({@link ConditionalStats}, R757) at plugin {@code setup()}, before any asset decodes:
 * the library's entity conditions into the engine's own condition map (R700), so an effect's {@code ApplyConditions}
 * and a stat's regeneration may name them too; the holder pass, the first-hit record and the forgetting of an
 * entity that left; the damage route term and the healing term the payouts ride; and the marks that keep the effect
 * family table in step with effect loads. Registration only. The two stores are the root's, with every framework
 * store. A failed registration rethrows (R700): every conditional stat would be gone.
 */
public final class ConditionBootstrap {

    private ConditionBootstrap() {
    }

    /** Call once from plugin {@code setup()}, after the per-type damage bonus step is installed. */
    public static void install(@Nonnull PluginBase plugin) {
        try {
            var conditions = plugin.getCodecRegistry(Condition.CODEC);
            conditions.register(ZigNightCondition.TYPE_NAME, ZigNightCondition.class, ZigNightCondition.codec());
            conditions.register(ZigFoesNearCondition.TYPE_NAME, ZigFoesNearCondition.class,
                    ZigFoesNearCondition.codec());
            conditions.register(ZigPlayersNearCondition.TYPE_NAME, ZigPlayersNearCondition.class,
                    ZigPlayersNearCondition.codec());
            conditions.register(ZigStatPercentCondition.TYPE_NAME, ZigStatPercentCondition.class,
                    ZigStatPercentCondition.codec());
            conditions.register(ZigMovingCondition.TYPE_NAME, ZigMovingCondition.class, ZigMovingCondition.codec());
            conditions.register(ZigFoeFamilyCondition.TYPE_NAME, ZigFoeFamilyCondition.class,
                    ZigFoeFamilyCondition.codec());
            conditions.register(ZigEffectFamilyCondition.TYPE_NAME, ZigEffectFamilyCondition.class,
                    ZigEffectFamilyCondition.codec());
            conditions.register(ZigHoldingCondition.TYPE_NAME, ZigHoldingCondition.class, ZigHoldingCondition.codec());
            conditions.register(ZigOpenSkyCondition.TYPE_NAME, ZigOpenSkyCondition.class, ZigOpenSkyCondition.codec());
            conditions.register(ZigHeightCondition.TYPE_NAME, ZigHeightCondition.class, ZigHeightCondition.codec());
            conditions.register(ZigCrouchingCondition.TYPE_NAME, ZigCrouchingCondition.class,
                    ZigCrouchingCondition.codec());
            plugin.getEntityStoreRegistry().registerSystem(new ConditionalStatSystems.HolderPass());
            plugin.getEntityStoreRegistry().registerSystem(new ConditionalStatSystems.FirstHitRecord());
            plugin.getEntityStoreRegistry().registerSystem(new ConditionalStatSystems.Left());
            TypedDamageBonuses.registerRouteTerm(ConditionalStats.ROUTE_TERM_OWNER, ConditionalStats::damagePoints);
            HealthUtil.installHealingTerm(ConditionalStats::healingPoints);
            plugin.getEventRegistry().register(LoadedAssetsEvent.class, EntityEffect.class,
                    (LoadedAssetsEvent<String, EntityEffect, IndexedLookupTableAssetMap<String, EntityEffect>> ev) ->
                            EffectFamilies.effectsLoaded(ev.getLoadedAssets()));
            plugin.getEventRegistry().register(RemovedAssetsEvent.class, EntityEffect.class,
                    (RemovedAssetsEvent<String, EntityEffect, IndexedLookupTableAssetMap<String, EntityEffect>> ev) ->
                            EffectFamilies.effectsRemoved(ev.getRemovedAssets()));
        } catch (Throwable t) {
            throw new IllegalStateException("[condition] could not register conditional stats", t);
        }
    }
}
