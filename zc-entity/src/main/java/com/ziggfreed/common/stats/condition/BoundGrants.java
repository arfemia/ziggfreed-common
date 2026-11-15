package com.ziggfreed.common.stats.condition;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entity.condition.Condition;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.stats.StatIndexCache;
import com.ziggfreed.common.stats.damage.TypedDamageBonuses;
import com.ziggfreed.common.util.SafeLog;

/**
 * Bound grants (R933, R934): stat modifiers that count only while their conditions hold on their holder, a talent or
 * a gear set tier bound {@code While} its weapon is in hand. A writer hands an entity's bound modifiers here per owner
 * ({@link #set}: the stat grant writer for a track's picks, the gear set engine for a tier, zc-track's counters for a
 * {@code PerStack} row); the conditional holder pass ({@code ConditionalStatSystems.HolderPass}: a player every tick,
 * so a weapon swap counts on the next swing; a creature on its {@code StatCarriers} cadence) reads each one's
 * conditions and puts its modifier on the stat under {@value #MODIFIER_PREFIX}{@code <owner>:<id>}, or takes it off,
 * only on a change, so every reader of the stat (a hit's crit step, a page) sees it as one more modifier. The
 * conditions are the engine's own entity conditions ({@code Condition.CODEC}), the one vocabulary a conditional stat's
 * {@code Holder} and an effect's {@code ApplyConditions} read, so a mod's own condition kind binds a grant too.
 *
 * <p><b>The hit term</b> (R945). A modifier whose conditions name a {@link HitAwareCondition} may also count on one
 * hit its holder dealt while it is off: when every one of its conditions holds on that hit (a hit-aware one asked on
 * the hit, every other as the pass reads it), the hit's reader adds its amount ({@link #hitPoints}): the per-type
 * damage bonus (a route term of {@code TypedDamageBonuses}), the crit, weak-spot and lifesteal steps, the conditional
 * stats' hit payouts and zc-track's procs. A modifier that is on is already on the stat and is never paid again; a
 * list with no hit-aware condition cannot flip on a hit and is never asked. Only an additive modifier on the stat's
 * maximum is paid on a hit (a talent's and a counter's grants are): a multiplying one counts only while it is on.
 *
 * <p>The key sits under the track grants' owner behind an {@code @}, like the conditional fold's, so the stat grant
 * writer leaves it alone and the 1.7.x rollback sweep of {@code zigtrack:} keys clears it (R755). The modifiers are
 * saved with the entity like any other, so an entity's first pass sweeps every one it finds that is not wanted now.
 * In memory only, keyed by the entity's reference: a holder that leaves its store is forgotten, and its next rebuild
 * (a player's ready settle, the gear set recompute) hands its modifiers over again. World thread.
 *
 * <p><b>Cost.</b> A hit by an entity with no hit-aware bound modifier costs each reader one map read; otherwise each
 * reader walks that entity's hit-aware modifiers (a handful) and asks the conditions only of an off one on its stat.
 * The pass reads each distinct condition list once (a pick's grants share theirs), {@code ZigHolding} answers from its
 * per-item memo, and the stat map is written only when a condition flips. A condition that throws counts as not
 * holding, logged once.
 */
public final class BoundGrants {

    /** The prefix of every modifier key this writes. */
    public static final String MODIFIER_PREFIX = "zigtrack:@bound:";

    /** The route term's owner name in the per-type damage step. */
    static final String ROUTE_TERM_OWNER = "ziggfreedcommon:bound";

    /** Whether a condition that threw has been logged: the pass runs every tick, so it logs once a process. */
    private static final AtomicBoolean CONDITION_FAILED_LOGGED = new AtomicBoolean();

    /**
     * One bound modifier: {@code id} is unique among its owner's and stays the same across rebuilds, {@code statId}
     * the stat it lands on, {@code modifier} what it adds while every one of {@code conditions} holds.
     */
    public record Bound(@Nonnull String id, @Nonnull String statId, @Nonnull StaticModifier modifier,
            @Nonnull Condition[] conditions) {

        /** A flat amount added to the stat's maximum while {@code conditions} hold (a talent's grant). */
        @Nonnull
        public static Bound additive(@Nonnull String id, @Nonnull String statId, double amount,
                @Nonnull Condition[] conditions) {
            return new Bound(id, statId, new StaticModifier(Modifier.ModifierTarget.MAX,
                    StaticModifier.CalculationType.ADDITIVE, (float) amount), conditions);
        }
    }

    /** What one bound modifier resolved to on a stat map, and whether it is on now. */
    static final class Entry {
        final String key;
        final int stat;
        final String statId;
        final StaticModifier modifier;
        final Condition[] conditions;
        /** Whether a condition of its list can answer a hit ({@link HitAwareCondition}). */
        final boolean hitAware;
        boolean on;

        Entry(@Nonnull String key, int stat, @Nonnull String statId, @Nonnull StaticModifier modifier,
                @Nonnull Condition[] conditions) {
            this.key = key;
            this.stat = stat;
            this.statId = statId;
            this.modifier = modifier;
            this.conditions = conditions;
            this.hitAware = HitAwareCondition.anyIn(conditions);
        }

        boolean sameAs(@Nonnull Entry other) {
            return stat == other.stat && modifier.getTarget() == other.modifier.getTarget()
                    && modifier.getCalculationType() == other.modifier.getCalculationType()
                    && Float.compare(modifier.getAmount(), other.modifier.getAmount()) == 0;
        }

        /** Is it an additive amount on the stat's maximum, the one shape a hit adds in? */
        boolean additiveOnMax() {
            return modifier.getTarget() == Modifier.ModifierTarget.MAX
                    && modifier.getCalculationType() == StaticModifier.CalculationType.ADDITIVE;
        }

        /** May a hit pay it: off (an on one is already on the stat), hit-aware, and additive on the maximum? */
        boolean paysOnHit() {
            return !on && hitAware && additiveOnMax();
        }
    }

    /** Which stats a hit's reader adds up: the stat at {@code stat}, id {@code statId}. */
    @FunctionalInterface
    interface HitLine {
        boolean counts(int stat, @Nonnull String statId);
    }

    /** Does a condition list hold on the hit being read? */
    @FunctionalInterface
    interface HitHolds {
        boolean holds(@Nonnull Condition[] conditions);
    }

    /** Each entity's bound modifiers by owner, then by modifier key. */
    private static final Map<Ref<EntityStore>, Map<String, Map<String, Entry>>> BY_REF = new ConcurrentHashMap<>();

    /** Each entity's hit-aware bound modifiers, every owner's, the ones a hit's reader walks; absent for none. */
    private static final Map<Ref<EntityStore>, List<Entry>> HIT_AWARE = new ConcurrentHashMap<>();

    /** Entities whose saved bound modifiers were swept this session. */
    private static final Map<Ref<EntityStore>, Boolean> SWEPT = new ConcurrentHashMap<>();

    private BoundGrants() {
    }

    /** Whether {@code key} is one this writes. */
    public static boolean writesKey(@Nullable String key) {
        return key != null && key.startsWith(MODIFIER_PREFIX);
    }

    /**
     * Make {@code owner}'s bound modifiers on the entity at {@code ref} exactly {@code bound}, replacing what it handed
     * over before: one no longer listed is taken off at once; one whose amount or shape changed under the same
     * conditions (a counter's stacks, a talent's rank) is written over at once while it is on, else taken off; a new
     * one waits for the next pass to read its conditions. A blank or unknown stat, or a zero or non-finite amount, is
     * passed over. World thread.
     */
    public static void set(@Nullable EntityStatMap stats, @Nonnull Ref<EntityStore> ref, @Nonnull String owner,
            @Nonnull List<Bound> bound) {
        Map<String, Map<String, Entry>> owners = BY_REF.get(ref);
        if (bound.isEmpty() && (owners == null || !owners.containsKey(owner))) {
            return;
        }
        if (owners == null) {
            owners = BY_REF.computeIfAbsent(ref, r -> new ConcurrentHashMap<>());
        }
        Map<String, Entry> before = owners.getOrDefault(owner, Map.of());
        Map<String, Entry> now = new HashMap<>();
        for (Bound one : bound) {
            float amount = one.modifier().getAmount();
            if (one.statId().isBlank() || !Float.isFinite(amount) || amount == 0.0f) {
                continue;
            }
            int index = StatIndexCache.resolve(one.statId());
            if (index == StatIndexCache.UNRESOLVED) {
                StatIndexCache.warnUnknownOnce(one.statId());
                continue;
            }
            String key = MODIFIER_PREFIX + owner + ":" + one.id();
            Entry entry = new Entry(key, index, one.statId(), one.modifier(), one.conditions());
            Entry old = before.get(key);
            if (old != null && old.sameAs(entry)) {
                // A modifier that stayed the same keeps its state.
                entry.on = old.on;
            } else if (old != null && old.on && stats != null) {
                if (old.conditions == entry.conditions) {
                    // Same conditions, which held at the last pass: written over now, so the stat never drops a tick.
                    if (old.stat != entry.stat) {
                        stats.removeModifier(old.stat, old.key);
                    }
                    stats.putModifier(entry.stat, entry.key, entry.modifier);
                    entry.on = true;
                } else {
                    // Other conditions: taken off, and the next pass reads them.
                    stats.removeModifier(old.stat, old.key);
                }
            }
            now.put(key, entry);
        }
        for (Entry old : before.values()) {
            if (!now.containsKey(old.key) && old.on && stats != null) {
                stats.removeModifier(old.stat, old.key);
            }
        }
        if (now.isEmpty()) {
            owners.remove(owner);
            if (owners.isEmpty()) {
                BY_REF.remove(ref);
            }
        } else {
            owners.put(owner, now);
        }
        indexHitAware(ref, BY_REF.get(ref));
    }

    /** Keep {@link #HIT_AWARE} in step with the entity's modifiers. */
    private static void indexHitAware(@Nonnull Ref<EntityStore> ref,
            @Nullable Map<String, Map<String, Entry>> owners) {
        List<Entry> hitAware = new ArrayList<>();
        if (owners != null) {
            for (Map<String, Entry> entries : owners.values()) {
                for (Entry entry : entries.values()) {
                    if (entry.hitAware) {
                        hitAware.add(entry);
                    }
                }
            }
        }
        if (hitAware.isEmpty()) {
            HIT_AWARE.remove(ref);
        } else {
            HIT_AWARE.put(ref, List.copyOf(hitAware));
        }
    }

    /** Whether the entity at {@code ref} holds any bound modifier. */
    public static boolean has(@Nullable Ref<EntityStore> ref) {
        return ref != null && BY_REF.containsKey(ref);
    }

    /**
     * Whether the entity at {@code ref} holds a bound modifier a hit may pay ({@link #hitPoints}): one whose conditions
     * name a {@link HitAwareCondition}. One map read: what a per-hit step's fast path asks before skipping an attacker
     * whose stats all read 0.
     */
    public static boolean hasHitTerms(@Nullable Ref<EntityStore> ref) {
        return ref != null && HIT_AWARE.containsKey(ref);
    }

    /**
     * What the bound modifiers of {@code attacker} that are off add to the stat at {@code stat} on this hit: the sum of
     * each additive amount on the stat's maximum whose conditions all hold on {@code damage}, a hit the attacker dealt
     * (a hit-aware one asked on the hit, every other as the pass reads it), at least one of them hit-aware. An on
     * modifier is already on the stat, so it is never in it. 0 for an attacker with none, or for
     * {@link StatIndexCache#UNRESOLVED}. World thread; never throws.
     */
    public static double hitPoints(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> attacker, int stat, @Nonnull Damage damage) {
        List<Entry> entries = HIT_AWARE.get(attacker);
        if (entries == null || stat == StatIndexCache.UNRESOLVED) {
            return 0.0;
        }
        try {
            return hitSum(entries, (index, statId) -> index == stat, holdsOn(accessor, attacker, damage));
        } catch (Throwable t) {
            return 0.0;
        }
    }

    /** {@link #hitPoints(ComponentAccessor, Ref, int, Damage)} for a stat id; 0 for one not loaded. */
    public static double hitPoints(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> attacker, @Nonnull String statId, @Nonnull Damage damage) {
        if (!HIT_AWARE.containsKey(attacker)) {
            return 0.0;
        }
        return hitPoints(accessor, attacker, StatIndexCache.resolve(statId), damage);
    }

    /**
     * The per-type damage step's route term ({@code TypedDamageBonuses.registerRouteTerm}): the summed percent of the
     * off bound modifiers of {@code attacker} whose stat's own damage line counts on this hit
     * ({@code TypedDamageBonuses.countsOn}: the hit's {@code DamageBonus_<Type>}, the every-type {@code DamageBonus}, a
     * consumer's registered source) and whose conditions all hold on it. Held with the route lines under the attacker's
     * {@code DamageBonusMax}. Never throws.
     */
    static double damagePoints(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> attacker,
            @Nullable Ref<EntityStore> target, @Nonnull Damage damage, @Nullable String causeId) {
        List<Entry> entries = HIT_AWARE.get(attacker);
        if (entries == null) {
            return 0.0;
        }
        try {
            return hitSum(entries,
                    (index, statId) -> TypedDamageBonuses.countsOn(accessor, attacker, damage, causeId, statId),
                    holdsOn(accessor, attacker, damage));
        } catch (Throwable t) {
            return 0.0;
        }
    }

    /**
     * May a hit pay the entity at {@code ref} a bound modifier on {@code statId}: a hit-aware additive one on the stat's
     * maximum? What the conditional holder pass asks before it drops a stat reading 0.
     */
    static boolean mayPayOnHit(@Nullable Ref<EntityStore> ref, @Nonnull String statId) {
        List<Entry> entries = ref == null ? null : HIT_AWARE.get(ref);
        if (entries == null) {
            return false;
        }
        int stat = StatIndexCache.resolve(statId);
        for (Entry entry : entries) {
            if (entry.stat == stat && entry.additiveOnMax()) {
                return true;
            }
        }
        return false;
    }

    /** The hit read every hit term asks of a condition list. */
    @Nonnull
    private static HitHolds holdsOn(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> attacker, @Nonnull Damage damage) {
        return conditions -> HitAwareCondition.allHold(accessor, attacker, conditions, damage,
                ConditionalStats.now(accessor));
    }

    /**
     * The hit term over {@code entries}: each one a hit may pay ({@link Entry#paysOnHit}: off, hit-aware, additive on
     * the maximum) whose stat {@code line} counts, its conditions asked last through {@code holds}. Pure over the
     * entries and the two reads.
     */
    static double hitSum(@Nonnull List<Entry> entries, @Nonnull HitLine line, @Nonnull HitHolds holds) {
        double sum = 0.0;
        for (Entry entry : entries) {
            if (!entry.paysOnHit() || !line.counts(entry.stat, entry.statId) || !holds.holds(entry.conditions)) {
                continue;
            }
            float amount = entry.modifier.getAmount();
            if (Float.isFinite(amount)) {
                sum += amount;
            }
        }
        return sum;
    }

    /**
     * Read every bound modifier's conditions on the entity at {@code ref} and put or take it off where that changed;
     * the entity's first pass also sweeps every saved {@value #MODIFIER_PREFIX} modifier not wanted now. Never throws.
     */
    static void evaluate(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref,
            @Nonnull EntityStatMap stats, @Nonnull Instant now) {
        Map<String, Map<String, Entry>> owners = BY_REF.get(ref);
        if (SWEPT.putIfAbsent(ref, Boolean.TRUE) == null) {
            sweep(stats, owners);
        }
        if (owners == null) {
            return;
        }
        // Every grant one pick or tier writes shares one condition list: read each list once a pass.
        Map<Condition[], Boolean> read = new IdentityHashMap<>();
        for (Map<String, Entry> entries : owners.values()) {
            for (Entry entry : entries.values()) {
                Boolean known = read.get(entry.conditions);
                boolean holds;
                if (known != null) {
                    holds = known;
                } else {
                    try {
                        holds = Nearby.allHold(accessor, ref, entry.conditions, now);
                    } catch (Throwable t) {
                        holds = false;
                        if (CONDITION_FAILED_LOGGED.compareAndSet(false, true)) {
                            SafeLog.warn("[bound] a bound grant's condition threw; it counts as not holding (logged "
                                    + "once): " + t);
                        }
                    }
                    read.put(entry.conditions, holds);
                }
                if (holds == entry.on) {
                    continue;
                }
                if (holds) {
                    stats.putModifier(entry.stat, entry.key, entry.modifier);
                } else {
                    stats.removeModifier(entry.stat, entry.key);
                }
                entry.on = holds;
            }
        }
    }

    /** Take off every saved bound modifier on {@code stats}; a wanted one is written again on this same pass. */
    private static void sweep(@Nonnull EntityStatMap stats, @Nullable Map<String, Map<String, Entry>> owners) {
        for (int i = 0; i < stats.size(); i++) {
            EntityStatValue value = stats.get(i);
            Map<String, Modifier> modifiers = value == null ? null : value.getModifiers();
            if (modifiers == null || modifiers.isEmpty()) {
                continue;
            }
            for (String key : new ArrayList<>(modifiers.keySet())) {
                if (writesKey(key)) {
                    stats.removeModifier(i, key);
                }
            }
        }
        if (owners != null) {
            for (Map<String, Entry> entries : owners.values()) {
                for (Entry entry : entries.values()) {
                    entry.on = false;
                }
            }
        }
    }

    /** Forget the entity at {@code ref} (it left its store). */
    public static void forget(@Nonnull Ref<EntityStore> ref) {
        BY_REF.remove(ref);
        HIT_AWARE.remove(ref);
        SWEPT.remove(ref);
    }

    /** Forget every entity (a test). */
    public static void clear() {
        BY_REF.clear();
        HIT_AWARE.clear();
        SWEPT.clear();
    }
}
