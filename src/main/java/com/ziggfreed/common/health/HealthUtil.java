package com.ziggfreed.common.health;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatsModule;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.ZiggfreedCommonPlugin;

/**
 * Mod-agnostic helpers for restoring an entity's vital stats through the NATIVE
 * {@code EntityStats} module - the same API the MMO Skill Tree health-tick path uses
 * ({@code RegenTickingSystem} / {@code AbilityHealService}): read the {@code Health}
 * (or {@code Mana}) {@link EntityStatValue} off the entity's {@link EntityStatMap} and
 * {@link EntityStatMap#addStatValue} the delta up to its {@code getMax()}. The single
 * seam a minigame uses to top a player off (e.g. fully heal on a round win) without
 * re-deriving the stat-map plumbing per mod.
 *
 * <p><b>World thread only.</b> Every method reads the {@link Store} and so must run on
 * the entity's world thread (a system tick or inside {@code world.execute}). Each call
 * is fully try-guarded: a missing stat map, an invalid ref, or any engine throw degrades
 * to a {@code false} return, never an exception into the caller.
 */
public final class HealthUtil {

    private HealthUtil() {
    }

    /**
     * Restore the entity's {@code Health} stat to its maximum (a full heal). No-op when the
     * entity is already at full health, has no stat map, or the ref is invalid.
     *
     * @return {@code true} if health was raised; {@code false} if already full / no stat map / on any error
     */
    public static boolean fullHeal(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        return restoreToMax(store, ref, DefaultEntityStatTypes.getHealth(), "fullHeal");
    }

    /**
     * Add {@code amount} to the entity's {@code Health} stat (the engine clamps to max). Mirrors the
     * MMO {@code AbilityHealService.applyInstant} instant heal. No-op for a non-positive amount, an
     * already-full target, a missing stat map, or an invalid ref.
     *
     * @return {@code true} if any health was added; {@code false} otherwise
     */
    public static boolean heal(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, float amount) {
        if (amount <= 0f || !ref.isValid()) {
            return false;
        }
        try {
            EntityStatValue health = healthStat(store, ref);
            if (health == null || health.get() >= health.getMax()) {
                return false;
            }
            statMap(store, ref).addStatValue(DefaultEntityStatTypes.getHealth(), amount);
            return true;
        } catch (Throwable t) {
            warn("heal", t);
            return false;
        }
    }

    /**
     * Restore both {@code Health} and {@code Mana} to their maxima (a full reset of the player's
     * vitals). Each stat is restored independently, so an entity without a mana stat still gets
     * its health topped off.
     *
     * @return {@code true} if either stat was raised
     */
    public static boolean fullRestore(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        boolean healed = restoreToMax(store, ref, DefaultEntityStatTypes.getHealth(), "fullRestore.health");
        boolean mana = restoreToMax(store, ref, DefaultEntityStatTypes.getMana(), "fullRestore.mana");
        return healed || mana;
    }

    // ---------------------------------------------------------------------
    // internals
    // ---------------------------------------------------------------------

    /** Raise a single stat (by its {@link DefaultEntityStatTypes} index) to its max; try-guarded to a no-op. */
    private static boolean restoreToMax(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                                        int statIndex, @Nonnull String label) {
        if (!ref.isValid()) {
            return false;
        }
        try {
            EntityStatMap stats = statMap(store, ref);
            if (stats == null) {
                return false;
            }
            EntityStatValue stat = stats.get(statIndex);
            if (stat == null) {
                return false;
            }
            float current = stat.get();
            float max = stat.getMax();
            if (current >= max) {
                return false;
            }
            stats.addStatValue(statIndex, max - current);
            return true;
        } catch (Throwable t) {
            warn(label, t);
            return false;
        }
    }

    private static EntityStatMap statMap(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        return store.getComponent(ref, EntityStatsModule.get().getEntityStatMapComponentType());
    }

    private static EntityStatValue healthStat(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        EntityStatMap stats = statMap(store, ref);
        return stats == null ? null : stats.get(DefaultEntityStatTypes.getHealth());
    }

    private static void warn(@Nonnull String label, @Nonnull Throwable t) {
        try {
            ZiggfreedCommonPlugin.LOGGER.atFine().log("HealthUtil." + label + " failed: " + t.getMessage());
        } catch (Throwable ignored) {
            // log manager absent (unit JVM) - swallow so the heal stays a no-op, never a throw.
        }
    }
}
