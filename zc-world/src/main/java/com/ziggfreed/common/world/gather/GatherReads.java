package com.ziggfreed.common.world.gather;

import java.util.concurrent.ThreadLocalRandom;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatsModule;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.stats.StatIndexCache;

/**
 * The reads every gather step shares: a stat's folded maximum (the substrate every percent stat of the family
 * keeps, base 0 plus each modifier gear, an effect or a talent grant puts on it), a ceiling stat's value with its
 * shipped asset's {@code Max} as the fallback, one chance draw, and the breaker's stance and game mode. World-thread
 * only, as every entity read is.
 */
final class GatherReads {

    private GatherReads() {
    }

    /** The entity's native stat map, or null when it carries none or cannot be read. */
    @Nullable
    static EntityStatMap stats(@Nonnull ComponentAccessor<EntityStore> store, @Nullable Ref<EntityStore> ref) {
        if (ref == null || !ref.isValid()) {
            return null;
        }
        try {
            return store.getComponent(ref, EntityStatsModule.get().getEntityStatMapComponentType());
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@code statId}'s folded maximum on {@code stats}, 0 for no map, an unknown stat or a stat it holds none of. */
    static double max(@Nullable EntityStatMap stats, @Nullable String statId) {
        if (stats == null || statId == null) {
            return 0.0;
        }
        int index = StatIndexCache.resolve(statId);
        if (index == StatIndexCache.UNRESOLVED) {
            return 0.0;
        }
        EntityStatValue value = stats.get(index);
        if (value == null) {
            return 0.0;
        }
        double max = value.getMax();
        return Double.isFinite(max) ? max : 0.0;
    }

    /**
     * A ceiling stat's value on {@code stats}: its folded maximum when the entity holds it, else the shipped asset's
     * own {@code Max}, else {@code fallback}.
     */
    static double ceiling(@Nullable EntityStatMap stats, @Nonnull String statId, double fallback) {
        int index = StatIndexCache.resolve(statId);
        if (index == StatIndexCache.UNRESOLVED) {
            return fallback;
        }
        if (stats != null) {
            EntityStatValue value = stats.get(index);
            if (value != null && Double.isFinite(value.getMax())) {
                return value.getMax();
            }
        }
        try {
            EntityStatType asset = EntityStatType.getAssetMap().getAsset(index);
            return asset == null ? fallback : asset.getMax();
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** One draw: true with {@code percent} in a hundred, never at or below 0, always at or above 100. */
    static boolean roll(double percent) {
        if (!(percent > 0.0)) {
            return false;
        }
        return percent >= 100.0 || ThreadLocalRandom.current().nextDouble() * 100.0 < percent;
    }

    /** Whether the entity is crouching right now; false when it cannot be told. */
    static boolean crouching(@Nonnull ComponentAccessor<EntityStore> store, @Nullable Ref<EntityStore> ref) {
        if (ref == null || !ref.isValid()) {
            return false;
        }
        try {
            MovementStatesComponent component = store.getComponent(ref, MovementStatesComponent.getComponentType());
            MovementStates states = component == null ? null : component.getMovementStates();
            return states != null && states.crouching;
        } catch (Throwable t) {
            return false;
        }
    }

    /** The player's game mode, or null when the entity is no player. */
    @Nullable
    static GameMode gameMode(@Nonnull ComponentAccessor<EntityStore> store, @Nullable Ref<EntityStore> ref) {
        if (ref == null || !ref.isValid()) {
            return null;
        }
        try {
            Player player = store.getComponent(ref, Player.getComponentType());
            return player == null ? null : player.getGameMode();
        } catch (Throwable t) {
            return null;
        }
    }
}
