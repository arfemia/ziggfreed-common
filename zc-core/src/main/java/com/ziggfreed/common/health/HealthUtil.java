package com.ziggfreed.common.health;

import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatsModule;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.event.NativeEventSeam;
import com.ziggfreed.common.util.SafeLog;

/**
 * Mod-agnostic helpers for restoring an entity's vital stats through the NATIVE
 * {@code EntityStats} module - the same API this package's {@link StatRegenSystem} and a consumer's ability heals
 * use: read the {@code Health}
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

    /**
     * The stat (whole percent points) that raises every heal a healer gives through
     * {@link #heal(Store, Ref, Ref, float)}. It ships from zc-entity ({@code Server/Entity/Stats/HealingPower.json}).
     */
    public static final String HEALING_POWER_STAT = "HealingPower";

    /** The lowest healing power that counts: at -100 a heal gives nothing, never harm. */
    public static final double MIN_HEALING_POWER = -100.0;

    /**
     * Healing power a module works out for one heal (a conditional healing stat read against the heal's target), in
     * whole percent points beside {@value #HEALING_POWER_STAT}. Asked only for a heal with a healer; answers 0 when
     * nothing applies and never throws.
     */
    @FunctionalInterface
    public interface HealingTerm {
        double points(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> healer,
                @Nonnull Ref<EntityStore> target);
    }

    /** The one term, or null for none (no conditional healing on a server whose modules add none). */
    @Nullable
    private static volatile HealingTerm healingTerm;

    /**
     * Where a heal a player gives is announced ({@link ZigHealGivenEvent}): the engine bus, unless a host or a test
     * routes it elsewhere ({@link NativeEventSeam#publishTo}).
     */
    @Nonnull
    public static final NativeEventSeam HEAL_EVENTS = new NativeEventSeam("[health]");

    private HealthUtil() {
    }

    /**
     * Install the one {@link HealingTerm} (zc-entity's conditional stats fill it at setup); null removes it. Set once,
     * at setup.
     */
    public static void installHealingTerm(@Nullable HealingTerm term) {
        healingTerm = term;
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
     * The one healing applier (R648): add {@code amount}, raised by {@code healer}'s {@value #HEALING_POWER_STAT},
     * to {@code target}'s {@code Health} through {@link #heal(Store, Ref, float)}. Every heal a player GIVES, on
     * themselves or on someone else, goes through here with the giver as {@code healer}; a heal with no giver (a
     * drain such as lifesteal, a regeneration, a full restore) passes {@code null} and is paid as asked. One read
     * of the healer's stat and one multiply on top of the plain heal; nothing extra for a null healer. A heal with a
     * healer that finds room is announced as it lands ({@link ZigHealGivenEvent}, before the health moves), so a
     * reader of heals given (a heal-given proc, healing XP) hears every one through this one door. It names no source:
     * a heal that earns its giver's skill passes one ({@link #heal(Store, Ref, Ref, float, String)}).
     *
     * @return {@code true} if any health was added; {@code false} otherwise
     */
    public static boolean heal(@Nonnull Store<EntityStore> store, @Nullable Ref<EntityStore> healer,
            @Nonnull Ref<EntityStore> target, float amount) {
        return heal(store, healer, target, amount, null);
    }

    /**
     * {@link #heal(Store, Ref, Ref, float)}, naming what raised the heal: {@code source} rides the heal's
     * {@link ZigHealGivenEvent}, so a reader can credit the skill that healed (a proc's heal names its talent's track,
     * a consumer's spell the skill it trains), the way a hit's weapon names the skill its damage trains. Null names
     * nothing: such a heal pays only a source that asks no qualifier. Ignored for a heal with no healer.
     *
     * @return {@code true} if any health was added; {@code false} otherwise
     */
    public static boolean heal(@Nonnull Store<EntityStore> store, @Nullable Ref<EntityStore> healer,
            @Nonnull Ref<EntityStore> target, float amount, @Nullable String source) {
        if (amount <= 0f) {
            return false;
        }
        float scaled = scaledHeal(amount, healingPower(store, healer) + termPoints(store, healer, target));
        if (healer != null && healer.isValid() && HEAL_EVENTS.listening(ZigHealGivenEvent.class)) {
            float restores = restores(store, target, scaled);
            if (restores > 0f) {
                String named = source == null || source.isBlank() ? null : source.trim();
                HEAL_EVENTS.fire("heal given", ZigHealGivenEvent.class,
                        () -> new ZigHealGivenEvent(store, healer, target, restores, named));
            }
        }
        return heal(store, target, scaled);
    }

    /**
     * What a heal of {@code amount} would restore on {@code target} now: the amount held to the room under its
     * maximum, 0 when it is full, unreadable or the ref is gone.
     */
    private static float restores(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> target, float amount) {
        try {
            EntityStatValue health = target.isValid() ? healthStat(store, target) : null;
            if (health == null) {
                return 0f;
            }
            return roomFor(health.get(), health.getMax(), amount);
        } catch (Throwable t) {
            return 0f;
        }
    }

    /** A heal of {@code amount} on {@code current} of {@code max}: what it restores, never past full. Pure. */
    static float roomFor(float current, float max, float amount) {
        if (!(amount > 0f) || !Float.isFinite(current) || !Float.isFinite(max)) {
            return 0f;
        }
        return Math.max(0f, Math.min(amount, max - current));
    }

    /** The installed term's points for a heal by {@code healer} on {@code target}; 0 with no healer or no term. */
    private static double termPoints(@Nonnull Store<EntityStore> store, @Nullable Ref<EntityStore> healer,
            @Nonnull Ref<EntityStore> target) {
        HealingTerm term = healingTerm;
        if (term == null || healer == null || !healer.isValid()) {
            return 0.0;
        }
        try {
            double points = term.points(store, healer, target);
            return Double.isFinite(points) ? points : 0.0;
        } catch (Throwable t) {
            warn("healingTerm", t);
            return 0.0;
        }
    }

    /**
     * {@code amount} raised by {@code powerPoints} of healing power (whole percent points: 20 heals a fifth more),
     * the power held at {@value #MIN_HEALING_POWER} so a heal never turns into harm. Pure, so a tooltip and the
     * applier read one rule; a power that is not a number counts as none.
     */
    public static float scaledHeal(float amount, double powerPoints) {
        if (!Double.isFinite(powerPoints) || powerPoints == 0.0) {
            return amount;
        }
        double factor = 1.0 + Math.max(MIN_HEALING_POWER, powerPoints) / 100.0;
        return (float) (amount * factor);
    }

    /**
     * {@code healer}'s healing power in whole percent points: the {@value #HEALING_POWER_STAT} stat's maximum (its
     * base plus every modifier gear, an effect or a grant put on it), 0 for no healer, a healer without the stat
     * or one that cannot be read.
     */
    public static double healingPower(@Nonnull Store<EntityStore> store, @Nullable Ref<EntityStore> healer) {
        if (healer == null || !healer.isValid()) {
            return 0.0;
        }
        try {
            int index = EntityStatType.getAssetMap().getIndex(HEALING_POWER_STAT);
            if (index < 0) {
                return 0.0;
            }
            EntityStatMap stats = statMap(store, healer);
            EntityStatValue power = stats == null ? null : stats.get(index);
            return power == null ? 0.0 : power.getMax();
        } catch (Throwable t) {
            warn("healingPower", t);
            return 0.0;
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
     * @param factor multiplicative scale on the post-balance max (e.g. {@code 2.0} = double max HP), nudged so
     *               the scaled max lands on a whole point
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
            stats.putModifier(hp, key, maxModifier(wholeMaxFactor(stats.get(hp), key, factor)));
            fillToMax(stats, hp); // heal to the new full max (once, on first application)
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
     * @param factor multiplicative scale on the post-balance max (e.g. {@code 2.0} = double max HP), nudged so
     *               the scaled max lands on a whole point
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
            stats.putModifier(hp, key, maxModifier(wholeMaxFactor(stats.get(hp), key, factor)));
            fillToMax(stats, hp); // heal to the new full max (once, on first application)
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
     *   <li>key ABSENT + {@code factor != 1.0} -> put the multiplicative MAX modifier + {@link #fillToMax} (the
     *       first-apply heal, identical to {@link #scaleMaxHealth}).</li>
     *   <li>key PRESENT + {@code factor == 1.0} -> {@code removeModifier} (the roll no longer scales HP; the
     *       engine recalculate clamps current HP down to the un-scaled max).</li>
     *   <li>key PRESENT + a DIFFERENT amount -> {@code putModifier} replace WITHOUT a heal (a shrink
     *       auto-clamps current HP via the recalculate; a re-fill here would full-heal on every retune).</li>
     *   <li>key PRESENT + the SAME amount, or {@code factor == 1.0} with no key -> no-op.</li>
     * </ul>
     *
     * <p>This is the RECONCILE seam for a persist-and-re-derive spawn hook (open-world mob scaling): call it
     * unconditionally with the freshly folded factor and stale HP never accumulates. World-thread only; fully
     * try-guarded (any engine throw degrades to {@code false}).
     *
     * @param holder the pre-add entity holder (must carry an {@link EntityStatMap})
     * @param factor multiplicative scale on the post-balance max ({@code 1.0} = no scale / remove any prior), nudged
     *               so the scaled max lands on a whole point
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

    /**
     * Fill the stat at {@code statIndex} to its max by adding the gap: the one way to heal after a modifier
     * write. Never {@link EntityStatMap#maximizeStatValue} there. On a shared stat such as {@code Health} the
     * engine folds a Maximize into the update queued just before it and rewrites that update's op
     * ({@code EntityStatMap.tryMergeUpdate}), so a {@code PutModifier} queued this tick never reaches the
     * entity's viewers: their max stays at the base, their bar empties while the entity still has health,
     * and its model stops animating. An Add folds only into an Init, Add or Set, so the {@code PutModifier}
     * goes out intact. Every heal in this class goes through here.
     *
     * <p>World thread only (or a pre-add {@link Holder}'s map); the caller try-guards.
     *
     * @return {@code true} if the stat was raised; {@code false} when it is missing or already full
     */
    public static boolean fillToMax(@Nonnull EntityStatMap stats, int statIndex) {
        if (statIndex < 0) {
            return false;
        }
        EntityStatValue stat = stats.get(statIndex);
        if (stat == null) {
            return false;
        }
        float gap = stat.getMax() - stat.get();
        if (!(gap > 0f)) {
            return false;
        }
        stats.addStatValue(statIndex, gap);
        return true;
    }

    // ---------------------------------------------------------------------
    // internals
    // ---------------------------------------------------------------------

    /** Shared reconcile over a resolved {@link EntityStatMap} (Holder + Ref forms delegate here). */
    private static boolean reconcileOnMap(@Nullable EntityStatMap stats, double factor,
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
        float amount = wholeMaxFactor(stats.get(hp), key, factor);
        if (existing instanceof StaticModifier sm && Math.abs(sm.getAmount() - amount) < 1.0e-4f) {
            return false; // already at the wanted amount
        }
        boolean firstApply = existing == null;
        stats.putModifier(hp, key, maxModifier(amount));
        if (firstApply) {
            fillToMax(stats, hp); // heal to the new full max ONLY on first apply; a replace/shrink clamps
        }
        return true;
    }

    /** The multiplicative Health-MAX modifier every scale seam puts under its key. */
    private static StaticModifier maxModifier(float amount) {
        return new StaticModifier(Modifier.ModifierTarget.MAX, StaticModifier.CalculationType.MULTIPLICATIVE, amount);
    }

    /**
     * The multiplier nearest {@code factor} whose scaled Health max lands on a whole point. Damage lands in
     * whole points, so a fractional max (36 x 4.8 = 172.8) leaves a living mob on a fraction of a point that a
     * rounded readout shows as 0. Worked over the max without {@code key}'s own modifier (the stat's asset max
     * plus every other additive MAX modifier, as the engine's {@code EntityStatValue.computeModifiers} folds
     * them), and nudged down until the engine's float product is not above the whole target, so the last
     * fraction is never a sliver above zero. Returned unchanged when another multiplicative MAX modifier
     * shares the stat (the engine sums multipliers, so no single amount fixes it) or the base is not positive.
     */
    private static float wholeMaxFactor(@Nullable EntityStatValue health, @Nonnull String key, double factor) {
        float asked = (float) factor;
        if (health == null) {
            return asked;
        }
        EntityStatType type = EntityStatType.getAssetMap().getAsset(health.getIndex());
        if (type == null) {
            return asked;
        }
        boolean hasAdditive = false;
        float additive = 0f;
        Map<String, Modifier> modifiers = health.getModifiers();
        if (modifiers != null) {
            for (Map.Entry<String, Modifier> entry : modifiers.entrySet()) {
                if (key.equals(entry.getKey()) || !(entry.getValue() instanceof StaticModifier sm)
                        || sm.getTarget() != Modifier.ModifierTarget.MAX) {
                    continue;
                }
                if (sm.getCalculationType() == StaticModifier.CalculationType.MULTIPLICATIVE) {
                    return asked; // the engine sums multipliers: another one leaves no single whole amount
                }
                hasAdditive = true;
                additive += sm.getAmount();
            }
        }
        // Summed first, then added once, in the engine's own order, so the float base matches its fold.
        float base = hasAdditive ? type.getMax() + additive : type.getMax();
        if (!(base > 0f)) {
            return asked;
        }
        float target = Math.max(1f, Math.round(base * factor));
        float amount = target / base;
        while (base * amount > target) {
            amount = Math.nextDown(amount);
        }
        return amount;
    }

    /** Raise a single stat (by its {@link DefaultEntityStatTypes} index) to its max; try-guarded to a no-op. */
    private static boolean restoreToMax(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                                        int statIndex, @Nonnull String label) {
        if (!ref.isValid()) {
            return false;
        }
        try {
            EntityStatMap stats = statMap(store, ref);
            return stats != null && fillToMax(stats, statIndex);
        } catch (Throwable t) {
            warn(label, t);
            return false;
        }
    }

    private static EntityStatMap statMap(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        return store.getComponent(ref, EntityStatsModule.get().getEntityStatMapComponentType());
    }

    /**
     * The entity's {@code Health} right now, or NaN when it has none or it cannot be read. What a damage producer
     * reads before the engine applies a hit (in the Filter group, which never moves health), so the hit can be
     * capped at what the target had left.
     */
    public static float current(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        try {
            EntityStatValue health = ref.isValid() ? healthStat(store, ref) : null;
            return health == null ? Float.NaN : health.get();
        } catch (Throwable t) {
            return Float.NaN;
        }
    }

    /**
     * The entity's {@code Health} maximum right now (its asset's Max with every modifier applied), or NaN when it has
     * none or it cannot be read: what a track source paying a creature's health bar reads at a kill or a hit.
     */
    public static float max(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        try {
            EntityStatValue health = ref.isValid() ? healthStat(store, ref) : null;
            return health == null ? Float.NaN : health.getMax();
        } catch (Throwable t) {
            return Float.NaN;
        }
    }

    private static EntityStatValue healthStat(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        EntityStatMap stats = statMap(store, ref);
        return stats == null ? null : stats.get(DefaultEntityStatTypes.getHealth());
    }

    private static void warn(@Nonnull String label, @Nonnull Throwable t) {
        SafeLog.fine("HealthUtil." + label + " failed: " + t.getMessage());
    }
}
