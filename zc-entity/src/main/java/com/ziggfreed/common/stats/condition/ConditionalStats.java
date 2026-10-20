package com.ziggfreed.common.stats.condition;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entity.condition.Condition;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.modules.time.TimeResource;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.stats.StatIndexCache;
import com.ziggfreed.common.stats.damage.TypedDamageBonuses;
import com.ziggfreed.common.util.SafeLog;

/**
 * The runtime of conditional stats ({@link ConditionalStatAsset}, R757): each holder's readings, kept a few times a
 * second by {@link ConditionalStatSystem}, and the reads every payout makes of them.
 *
 * <p><b>A holder's reading</b>, per stat it holds (its value off the stat's maximum, where gear, effects and grants
 * accumulate, never 0): whether its {@code Holder} conditions hold (and have held for {@code HoldSeconds}) and its
 * {@code PerFoe} count. Only a stat the holder holds is asked anything, so a player with none costs one stat read per
 * conditional stat each pass. The readings are transient and single-process, dropped when the holder leaves its store.
 *
 * <p><b>The payouts.</b> {@code Onto} is written here, on the pass: one native {@code StaticModifier} keyed
 * {@value #MODIFIER_PREFIX}{@code <stat>} on each named stat, written or removed only when it changes, so whatever
 * reads those stats (the resistance reconciler, the movement pass) never knows the condition. The modifier is saved
 * with the entity like any other, so a holder's first pass after it enters a store sweeps every one it finds before
 * writing what it wants. It sits under the track grants' {@code zigtrack:} owner because it lands on stats 1.7.x
 * knows too ({@code DamageResist_<Type>}): the 1.7.x rollback sweep clears every saved {@code zigtrack:} modifier when
 * a player is ready (R755), so a rolled-back server never keeps a conditional resistance as a permanent one. The
 * {@code @} keeps it apart from every grant key ({@code zigtrack:<track>:<pick>}): no usable track id holds one (it is
 * a seasonal run's separator), and the stat grant writer leaves this one prefix alone ({@code StatGrantWriter}), so a
 * grant rebuild never takes it off. {@code Damage} is {@link #damagePoints}, the route term the library's
 * damage step adds per hit; {@code Healing} is {@link #healingPoints}, the term the one healing path adds; a file with
 * neither is read through {@link #pointsOnHit}. Every payout is held to its file's {@code Max}. A hit-time read is a
 * lookup over the holder's own readings, never a scan of the world.
 */
public final class ConditionalStats {

    /**
     * The prefix of every modifier the {@code Onto} fold writes, {@code zigtrack:@cond:<stat>}: under the track
     * grants' owner, so the 1.7.x rollback sweep of {@code zigtrack:} keys clears it (R755), and behind an {@code @}
     * no grant key can carry.
     */
    public static final String MODIFIER_PREFIX = "zigtrack:@cond:";

    /** Is {@code key} a modifier the {@code Onto} fold writes (so no other writer owns it)? */
    public static boolean writesKey(@Nullable String key) {
        return key != null && key.startsWith(MODIFIER_PREFIX);
    }

    /** The route term's owner name in the damage step. */
    public static final String ROUTE_TERM_OWNER = "ziggfreedcommon:conditional";

    /** How often a holder's conditions are read again, in nanoseconds: four times a second. */
    public static final long EVALUATE_EVERY_NANOS = 250_000_000L;

    /** One live file, its payouts resolved. */
    record Def(@Nonnull String fileId, @Nonnull String statId, @Nonnull ConditionalStatAsset asset,
            @Nonnull List<String> onto, @Nullable ConditionalStatAsset.Damage damage, boolean healing) {

        @Nonnull
        String key() {
            return statId.toLowerCase(Locale.ROOT);
        }
    }

    /** Every live file, and each by its stat (lower-cased). */
    record Defs(@Nonnull List<Def> all, @Nonnull Map<String, Def> byStat) {
        static final Defs EMPTY = new Defs(List.of(), Map.of());
    }

    /** One holder's reading of one stat. */
    static final class Reading {
        @Nonnull final Def def;
        double value;
        boolean holds;
        double factor;
        /** When the holder conditions began to hold without a break, or -1 while they do not. */
        long since = -1L;

        Reading(@Nonnull Def def) {
            this.def = def;
        }
    }

    /** One holder's state: its readings, the {@code Onto} modifiers it wrote, when it is read next. */
    private static final class HolderState {
        long nextNanos;
        int generation = -1;
        boolean swept;
        @Nonnull volatile Map<String, Reading> readings = Map.of();
        @Nonnull final Map<OntoKey, Float> written = new HashMap<>();
    }

    /** One {@code Onto} modifier: the stat it sits on and its key. */
    private record OntoKey(int statIndex, @Nonnull String key) {
    }

    @Nonnull
    private static volatile Defs defs = Defs.EMPTY;

    private static volatile int generation;

    private static final Map<Ref<EntityStore>, HolderState> HOLDERS = new ConcurrentHashMap<>();

    private static final AtomicBoolean PASS_FAILURE_WARNED = new AtomicBoolean();

    private ConditionalStats() {
    }

    // ==================== The folded files ====================

    /** Rebuild the live view from the folded files; every holder reads again on its next pass. */
    public static void reload() {
        Map<String, Def> byStat = new LinkedHashMap<>();
        for (Map.Entry<String, ConditionalStatAsset> entry : ConditionalStatConfig.getInstance().all().entrySet()) {
            ConditionalStatAsset asset = entry.getValue();
            String statId = asset == null ? null : asset.statId();
            if (asset == null || asset.isAbstract() || !asset.isEnabled() || statId == null) {
                continue;
            }
            ConditionalStatAsset.Pays pays = asset.pays();
            List<String> onto = pays == null || asset.asksOfTarget() ? List.of() : List.copyOf(pays.onto());
            Def def = new Def(entry.getKey(), statId, asset, onto, pays == null ? null : pays.damage(),
                    pays != null && pays.healing());
            byStat.putIfAbsent(def.key(), def);
        }
        defs = new Defs(List.copyOf(byStat.values()), Map.copyOf(byStat));
        generation++;
    }

    /** The live stat ids, as their files name them (an audit, a test). */
    @Nonnull
    public static List<String> statIds() {
        List<String> out = new ArrayList<>();
        for (Def def : defs.all()) {
            out.add(def.statId());
        }
        return out;
    }

    /** Is {@code statId} a live conditional stat (any case)? */
    public static boolean isConditional(@Nullable String statId) {
        return statId != null && defs.byStat().containsKey(statId.toLowerCase(Locale.ROOT));
    }

    // ==================== The holder pass ====================

    /**
     * Read the holder at {@code ref} again when its time has come (or the files moved): its value for every live
     * conditional stat, each held one's conditions and count, and the {@code Onto} modifiers that changed. World
     * thread; never throws.
     */
    static void evaluate(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref,
            @Nonnull EntityStatMap stats, @Nonnull Instant now, long nanos) {
        try {
            HolderState state = HOLDERS.computeIfAbsent(ref, r -> new HolderState());
            if (!state.swept) {
                sweep(stats);
                state.swept = true;
            }
            int gen = generation;
            if (state.generation == gen && nanos < state.nextNanos) {
                return;
            }
            state.generation = gen;
            state.nextNanos = nanos + EVALUATE_EVERY_NANOS;
            Map<String, Reading> previous = state.readings;
            Map<String, Reading> next = new HashMap<>();
            Map<OntoKey, Float> wanted = new HashMap<>();
            for (Def def : defs.all()) {
                double value = readMax(stats, def.statId());
                if (value == 0.0) {
                    continue;
                }
                Reading reading = previous.get(def.key());
                if (reading == null || reading.def != def) {
                    reading = new Reading(def);
                }
                read(accessor, ref, reading, value, now, nanos);
                next.put(def.key(), reading);
                if (!def.onto().isEmpty() && reading.holds && reading.factor > 0.0) {
                    float amount = (float) paid(reading);
                    String key = MODIFIER_PREFIX + def.key();
                    for (String onto : def.onto()) {
                        int index = StatIndexCache.resolve(onto);
                        if (index != StatIndexCache.UNRESOLVED) {
                            wanted.merge(new OntoKey(index, key), amount, Float::sum);
                        }
                    }
                }
            }
            state.readings = next.isEmpty() ? Map.of() : Map.copyOf(next);
            writeOnto(stats, state.written, wanted);
        } catch (Throwable t) {
            if (PASS_FAILURE_WARNED.compareAndSet(false, true)) {
                SafeLog.warn("[condition] reading a holder's conditional stats failed (named once): " + t);
            } else {
                SafeLog.fine("[condition] a holder pass failed: " + t.getMessage());
            }
        }
    }

    /** One reading: the value, whether the holder conditions hold and for how long, and the foe count. */
    private static void read(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref,
            @Nonnull Reading reading, double value, @Nonnull Instant now, long nanos) {
        ConditionalStatAsset asset = reading.def.asset();
        reading.value = value;
        boolean conditions = Nearby.allHold(accessor, ref, asset.holderConditions(), now);
        reading.since = conditions ? (reading.since < 0L ? nanos : reading.since) : -1L;
        reading.holds = conditions && heldLongEnough(reading.since, nanos, asset.holdSeconds());
        ConditionalStatAsset.PerFoe perFoe = asset.perFoe();
        if (!reading.holds || perFoe == null) {
            reading.factor = reading.holds ? 1.0 : 0.0;
        } else {
            reading.factor = Math.min(perFoe.upTo(),
                    Nearby.foes(accessor, ref, perFoe.radius(), perFoe.conditions(), now, perFoe.upTo()));
        }
    }

    /** Have conditions that began holding at {@code since} held for {@code seconds} by {@code nanos}? Pure. */
    static boolean heldLongEnough(long since, long nanos, double seconds) {
        if (since < 0L) {
            return false;
        }
        return seconds <= 0.0 || nanos - since >= (long) (seconds * 1_000_000_000L);
    }

    /** Put every wanted {@code Onto} modifier that changed, and remove every written one no longer wanted. */
    private static void writeOnto(@Nonnull EntityStatMap stats, @Nonnull Map<OntoKey, Float> written,
            @Nonnull Map<OntoKey, Float> wanted) {
        if (written.isEmpty() && wanted.isEmpty()) {
            return;
        }
        for (OntoKey gone : new ArrayList<>(written.keySet())) {
            if (!wanted.containsKey(gone)) {
                stats.removeModifier(gone.statIndex(), gone.key());
                written.remove(gone);
            }
        }
        for (Map.Entry<OntoKey, Float> put : wanted.entrySet()) {
            Float before = written.get(put.getKey());
            if (before != null && before.floatValue() == put.getValue().floatValue()) {
                continue;
            }
            stats.putModifier(put.getKey().statIndex(), put.getKey().key(), new StaticModifier(
                    Modifier.ModifierTarget.MAX, StaticModifier.CalculationType.ADDITIVE, put.getValue()));
            written.put(put.getKey(), put.getValue());
        }
    }

    /** Remove every {@value #MODIFIER_PREFIX} modifier on the entity (one saved with it from an earlier session). */
    private static void sweep(@Nonnull EntityStatMap stats) {
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
    }

    /** Forget the holder at {@code ref} (it left its store). */
    public static void forget(@Nonnull Ref<EntityStore> ref) {
        HOLDERS.remove(ref);
    }

    // ==================== The reads ====================

    /**
     * The damage step's route term: the summed percent of every {@code Damage} stat {@code attacker} holds that counts
     * on this hit (its holder conditions holding, its types matching the cause, its target and hit reads met, the
     * line it follows counting), each times its {@code PerFoe} count. 0 for a holder with none. Never throws.
     */
    public static double damagePoints(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> attacker, @Nullable Ref<EntityStore> target, @Nonnull Damage damage,
            @Nullable String causeId) {
        HolderState state = HOLDERS.get(attacker);
        if (state == null || state.readings.isEmpty()) {
            return 0.0;
        }
        try {
            double sum = 0.0;
            for (Reading reading : state.readings.values()) {
                ConditionalStatAsset.Damage pays = reading.def.damage();
                if (pays == null || !reading.holds || !(reading.factor > 0.0)
                        || !typeMatches(pays.types(), causeId)
                        || !countsOnHit(accessor, attacker, target, damage, reading.def)) {
                    continue;
                }
                String like = pays.like();
                if (like != null && !TypedDamageBonuses.countsOn(accessor, attacker, damage, causeId, like)) {
                    continue;
                }
                sum += paid(reading);
            }
            return sum;
        } catch (Throwable t) {
            return 0.0;
        }
    }

    /**
     * The healing path's term: the summed percent of every {@code Healing} stat {@code healer} holds that counts on
     * a heal of {@code target} (its holder conditions holding, its target and {@code OnSelf} reads met). Never
     * throws.
     */
    public static double healingPoints(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nullable Ref<EntityStore> healer, @Nonnull Ref<EntityStore> target) {
        HolderState state = healer == null ? null : HOLDERS.get(healer);
        if (state == null || state.readings.isEmpty()) {
            return 0.0;
        }
        try {
            double sum = 0.0;
            for (Reading reading : state.readings.values()) {
                if (!reading.def.healing() || !reading.holds || !(reading.factor > 0.0)) {
                    continue;
                }
                ConditionalStatAsset asset = reading.def.asset();
                ConditionalStatAsset.Hit hit = asset.hit();
                Boolean onSelf = hit == null ? null : hit.onSelf();
                if (onSelf != null && onSelf != Nearby.Targets.same(healer, target)) {
                    continue;
                }
                if (!Nearby.allHold(accessor, target, asset.targetConditions(), now(accessor))) {
                    continue;
                }
                sum += paid(reading);
            }
            return sum;
        } catch (Throwable t) {
            return 0.0;
        }
    }

    /**
     * What {@code statId} pays {@code attacker} on this hit, for a consumer that reads a conditional stat itself (a
     * crit chance on a frozen foe): its value times its count, held to its {@code Max}, when its holder conditions
     * hold and its target and hit reads are met, else 0. Its types and {@code Like} are not asked. Never throws.
     */
    public static double pointsOnHit(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> attacker, @Nullable Ref<EntityStore> target, @Nonnull Damage damage,
            @Nonnull String statId) {
        HolderState state = HOLDERS.get(attacker);
        Reading reading = state == null ? null : state.readings.get(statId.toLowerCase(Locale.ROOT));
        if (reading == null || !reading.holds || !(reading.factor > 0.0)) {
            return 0.0;
        }
        try {
            return countsOnHit(accessor, attacker, target, damage, reading.def) ? paid(reading) : 0.0;
        } catch (Throwable t) {
            return 0.0;
        }
    }

    /**
     * What a reading pays: its value times its count, held to its file's {@code Max} (R761). The engine clamps a
     * stat's current value to its maximum but never the maximum its modifiers raise, where these stats accumulate, so
     * every payout holds it here.
     */
    private static double paid(@Nonnull Reading reading) {
        return Math.min(reading.value * reading.factor, reading.def.asset().max());
    }

    /** Do a file's target conditions and hit reads all hold on this hit? */
    private static boolean countsOnHit(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> attacker, @Nullable Ref<EntityStore> target, @Nonnull Damage damage,
            @Nonnull Def def) {
        Condition[] conditions = def.asset().targetConditions();
        if (conditions.length > 0) {
            if (target == null || !target.isValid()) {
                return false;
            }
            if (!Nearby.allHold(accessor, target, conditions, now(accessor))) {
                return false;
            }
        }
        return HitReads.meets(accessor, attacker, target, damage, def.asset().hit());
    }

    /**
     * The clock entity conditions are asked against: the world's own time ({@code TimeResource}), which the engine's
     * stat regeneration asks its conditions with and its damage records stamp; the wall clock when it is not there.
     */
    @Nonnull
    public static Instant now(@Nonnull ComponentAccessor<EntityStore> accessor) {
        try {
            TimeResource time = accessor.getResource(TimeResource.getResourceType());
            Instant now = time == null ? null : time.getNow();
            return now == null ? Instant.now() : now;
        } catch (Throwable t) {
            return Instant.now();
        }
    }

    /** Does a hit of {@code causeId} match any of {@code types} ({@code "*"} matches every hit)? */
    static boolean typeMatches(@Nonnull List<String> types, @Nullable String causeId) {
        for (String type : types) {
            if (ConditionalStatAsset.EVERY_TYPE.equals(type)) {
                return true;
            }
        }
        if (causeId == null) {
            return false;
        }
        try {
            DamageCause cause = DamageCause.getAssetMap().getAsset(causeId);
            if (cause == null) {
                return false;
            }
            for (String type : types) {
                DamageCause key = DamageCause.getAssetMap().getAsset(type);
                if (key != null && cause.isOrInherits(key)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** A stat's current maximum, where modifiers accumulate; 0 when unregistered, absent or not finite. */
    private static double readMax(@Nonnull EntityStatMap stats, @Nonnull String statId) {
        int index = StatIndexCache.resolve(statId);
        if (index == StatIndexCache.UNRESOLVED) {
            return 0.0;
        }
        EntityStatValue value = stats.get(index);
        double max = value == null ? 0.0 : value.getMax();
        return Double.isFinite(max) ? max : 0.0;
    }

    /** Forget every holder (a test). */
    static void clearHolders() {
        HOLDERS.clear();
    }
}
