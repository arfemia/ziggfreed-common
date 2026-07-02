package com.ziggfreed.common.health;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatsModule;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.ZiggfreedCommonPlugin;

/**
 * Mod-agnostic helpers for restoring an entity's vital stats through the NATIVE
 * {@code EntityStats} module - the same API the MMO Skill Tree health-tick path uses
 * ({@code RegenTickingSystem} / {@code AbilityHealService}): read the {@code Health}
 * (or {@code Mana}) {@link EntityStatValue} off the entity's {@link EntityStatMap} and
 * {@link EntityStatMap#addStatValue} the delta up to its {@code getMax()}. The single
 * seam a minigame uses to top a player off (e.g. fully heal on a round win) without
 * re-deriving the stat-map plumbing per mod. {@link #scaleMaxHealth} additionally raises an
 * entity's Health-stat MAX at runtime (a multiplicative modifier) for per-encounter scaling.
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

    /**
     * Raise the entity's {@code Health} stat MAX by {@code factor} (a multiplicative MAX modifier keyed
     * by {@code key}) and heal it to the new max. The single seam for per-encounter HP scaling (e.g. a
     * boss whose HP grows with party size / difficulty) without re-deriving the modifier plumbing per mod.
     *
     * <p><b>Idempotent</b> per entity: a no-op (returns {@code false}) when the modifier under {@code key}
     * is already present (so it scales each entity exactly once - call it freely every tick), the Health
     * stat is not yet present (balancing not done this tick), {@code factor == 1.0}, or the ref is invalid.
     * Because it heals to the new max only on the call that newly applies the modifier, repeat calls never
     * re-heal (which would make a target unkillable).
     *
     * <p>World thread only; fully try-guarded (any engine throw degrades to {@code false}).
     *
     * @param factor multiplicative scale on the post-balance max (e.g. {@code 2.0} = double max HP)
     * @param key    unique, mod-prefixed modifier key (the idempotency handle; avoid engine stat keys)
     * @return {@code true} if the scale was newly applied this call; {@code false} otherwise
     */
    public static boolean scaleMaxHealth(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                                         double factor, @Nonnull String key) {
        if (factor == 1.0 || !ref.isValid()) {
            return false;
        }
        try {
            EntityStatMap stats = statMap(store, ref);
            if (stats == null) {
                return false;
            }
            int hp = DefaultEntityStatTypes.getHealth();
            if (stats.get(hp) == null || stats.getModifier(hp, key) != null) {
                return false; // stat not ready (balancing pending), or already scaled (per-entity)
            }
            stats.putModifier(hp, key, new StaticModifier(
                    Modifier.ModifierTarget.MAX, StaticModifier.CalculationType.MULTIPLICATIVE, (float) factor));
            stats.maximizeStatValue(hp); // heal to the new full max (once, on first application)
            return true;
        } catch (Throwable t) {
            warn("scaleMaxHealth", t);
            return false;
        }
    }

    /**
     * Ref-less {@link #scaleMaxHealth(Store, Ref, double, String)}: raise the {@code Health} stat MAX
     * on a pre-add {@link Holder} (before the entity has a valid {@link Ref}), then heal to the new max.
     * The seam for scaling a mob's HP INSIDE a {@code HolderSystem.onEntityAdd} spawn hook, where the
     * entity is not yet added and no ref exists (mirrors the {@code Holder} teardown path other stat
     * utils expose). Reads the {@link EntityStatMap} straight off the holder.
     *
     * <p><b>Idempotent</b> per holder (guarded by {@code key}): a no-op (returns {@code false}) when the
     * modifier under {@code key} is already present, the Health stat is not yet present (balancing not
     * done), or {@code factor == 1.0}. Fully try-guarded (any engine throw degrades to {@code false}).
     *
     * @param holder the pre-add entity holder (must carry an {@link EntityStatMap})
     * @param factor multiplicative scale on the post-balance max (e.g. {@code 2.0} = double max HP)
     * @param key    unique, mod-prefixed modifier key (the idempotency handle; avoid engine stat keys)
     * @return {@code true} if the scale was newly applied this call; {@code false} otherwise
     */
    public static boolean scaleMaxHealth(@Nonnull Holder<EntityStore> holder, double factor, @Nonnull String key) {
        if (factor == 1.0) {
            return false;
        }
        try {
            EntityStatMap stats = holder.getComponent(EntityStatsModule.get().getEntityStatMapComponentType());
            if (stats == null) {
                return false;
            }
            int hp = DefaultEntityStatTypes.getHealth();
            if (stats.get(hp) == null || stats.getModifier(hp, key) != null) {
                return false; // stat not ready (balancing pending), or already scaled (per-holder)
            }
            stats.putModifier(hp, key, new StaticModifier(
                    Modifier.ModifierTarget.MAX, StaticModifier.CalculationType.MULTIPLICATIVE, (float) factor));
            stats.maximizeStatValue(hp); // heal to the new full max (once, on first application)
            return true;
        } catch (Throwable t) {
            warn("scaleMaxHealth(holder)", t);
            return false;
        }
    }

    /**
     * RECONCILE the entity's {@code Health}-stat MAX modifier under {@code key} to match {@code factor}, on a
     * pre-add {@link Holder}. Unlike {@link #scaleMaxHealth(Holder, double, String)} (which is add-only and
     * no-ops when the key is present), this converges the stored modifier to whatever the CURRENT roll wants,
     * so a re-derived scale on chunk reload never strands a stale value:
     *
     * <ul>
     *   <li>key ABSENT + {@code factor != 1.0} -> put the multiplicative MAX modifier + {@code maximize} (the
     *       first-apply heal, identical to {@link #scaleMaxHealth}).</li>
     *   <li>key PRESENT + {@code factor == 1.0} -> {@code removeModifier} (the roll no longer scales HP; the
     *       engine recalculate clamps current HP down to the un-scaled max).</li>
     *   <li>key PRESENT + a DIFFERENT amount -> {@code putModifier} replace WITHOUT maximize (a shrink
     *       auto-clamps current HP via the recalculate; a re-maximize here would full-heal on every retune).</li>
     *   <li>key PRESENT + the SAME amount, or {@code factor == 1.0} with no key -> no-op.</li>
     * </ul>
     *
     * <p>This is the RECONCILE seam for a persist-and-re-derive spawn hook (open-world mob scaling): call it
     * unconditionally with the freshly folded factor and stale HP never accumulates. World-thread only; fully
     * try-guarded (any engine throw degrades to {@code false}).
     *
     * @param holder the pre-add entity holder (must carry an {@link EntityStatMap})
     * @param factor multiplicative scale on the post-balance max ({@code 1.0} = no scale / remove any prior)
     * @param key    unique, mod-prefixed modifier key (the reconcile handle; avoid engine stat keys)
     * @return {@code true} if the modifier state changed this call (put / replaced / removed); {@code false} otherwise
     */
    public static boolean reconcileMaxHealth(@Nonnull Holder<EntityStore> holder, double factor, @Nonnull String key) {
        try {
            EntityStatMap stats = holder.getComponent(EntityStatsModule.get().getEntityStatMapComponentType());
            return reconcileOnMap(stats, factor, key);
        } catch (Throwable t) {
            warn("reconcileMaxHealth(holder)", t);
            return false;
        }
    }

    /**
     * Ref form of {@link #reconcileMaxHealth(Holder, double, String)}: reconcile the {@code Health}-stat MAX
     * modifier under {@code key} on an already-added entity (the seam an admin purge / a post-add sweep uses).
     * Passing {@code factor == 1.0} strips the modifier. World-thread only; fully try-guarded.
     *
     * @return {@code true} if the modifier state changed this call; {@code false} otherwise
     */
    public static boolean reconcileMaxHealth(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                                             double factor, @Nonnull String key) {
        if (!ref.isValid()) {
            return false;
        }
        try {
            return reconcileOnMap(statMap(store, ref), factor, key);
        } catch (Throwable t) {
            warn("reconcileMaxHealth(ref)", t);
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // internals
    // ---------------------------------------------------------------------

    /** Shared reconcile over a resolved {@link EntityStatMap} (Holder + Ref forms delegate here). */
    private static boolean reconcileOnMap(@javax.annotation.Nullable EntityStatMap stats, double factor,
                                          @Nonnull String key) {
        if (stats == null) {
            return false;
        }
        int hp = DefaultEntityStatTypes.getHealth();
        if (stats.get(hp) == null) {
            return false; // stat not ready (balancing pending)
        }
        Modifier existing = stats.getModifier(hp, key);
        if (factor == 1.0) {
            if (existing == null) {
                return false; // nothing to strip
            }
            stats.removeModifier(hp, key); // recalculate clamps current HP to the un-scaled max
            return true;
        }
        if (existing instanceof StaticModifier sm && Math.abs(sm.getAmount() - (float) factor) < 1.0e-4f) {
            return false; // already at the wanted amount
        }
        boolean firstApply = existing == null;
        stats.putModifier(hp, key, new StaticModifier(
                Modifier.ModifierTarget.MAX, StaticModifier.CalculationType.MULTIPLICATIVE, (float) factor));
        if (firstApply) {
            stats.maximizeStatValue(hp); // heal to the new full max ONLY on first apply; a replace/shrink clamps
        }
        return true;
    }

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
