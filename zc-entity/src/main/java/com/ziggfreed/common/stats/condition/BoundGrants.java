package com.ziggfreed.common.stats.condition;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entity.condition.Condition;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.stats.StatIndexCache;

/**
 * Bound grants (R933, R934): stat modifiers that count only while their conditions hold on their holder, a talent or
 * a gear set tier bound {@code While} its weapon is in hand. A writer hands an entity's bound modifiers here per owner
 * ({@link #set}: the stat grant writer for a track's picks, the gear set engine for a tier); the conditional holder
 * pass ({@code ConditionalStatSystems.HolderPass}, a few times a second for a player) reads each one's conditions and
 * puts its modifier on the stat under {@value #MODIFIER_PREFIX}{@code <owner>:<id>}, or takes it off, only on a
 * change, so every reader of the stat (a hit's crit step, a page) sees it as one more modifier. The conditions are
 * the engine's own entity conditions ({@code Condition.CODEC}), the one vocabulary a conditional stat's
 * {@code Holder} and an effect's {@code ApplyConditions} read, so a mod's own condition kind binds a grant too.
 *
 * <p>The key sits under the track grants' owner behind an {@code @}, like the conditional fold's, so the stat grant
 * writer leaves it alone and the 1.7.x rollback sweep of {@code zigtrack:} keys clears it (R755). The modifiers are
 * saved with the entity like any other, so an entity's first pass sweeps every one it finds that is not wanted now.
 * In memory only, keyed by the entity's reference: a holder that leaves its store is forgotten, and its next rebuild
 * (a player's ready settle, the gear set recompute) hands its modifiers over again. World thread.
 *
 * <p><b>Cost.</b> Nothing on a hit: a bound modifier sits on the stat like gear's, so every per-hit read stays one
 * stat read. The pass reads each distinct condition list once (a pick's grants share theirs), {@code ZigHolding}
 * answers from its per-item memo, and the stat map is written only when a condition flips.
 */
public final class BoundGrants {

    /** The prefix of every modifier key this writes. */
    public static final String MODIFIER_PREFIX = "zigtrack:@bound:";

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
    private static final class Entry {
        final String key;
        final int stat;
        final StaticModifier modifier;
        final Condition[] conditions;
        boolean on;

        Entry(@Nonnull String key, int stat, @Nonnull StaticModifier modifier, @Nonnull Condition[] conditions) {
            this.key = key;
            this.stat = stat;
            this.modifier = modifier;
            this.conditions = conditions;
        }

        boolean sameAs(@Nonnull Entry other) {
            return stat == other.stat && modifier.getTarget() == other.modifier.getTarget()
                    && modifier.getCalculationType() == other.modifier.getCalculationType()
                    && Float.compare(modifier.getAmount(), other.modifier.getAmount()) == 0;
        }
    }

    /** Each entity's bound modifiers by owner, then by modifier key. */
    private static final Map<Ref<EntityStore>, Map<String, Map<String, Entry>>> BY_REF = new ConcurrentHashMap<>();

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
     * over before: one no longer listed or changed is taken off at once, a new one waits for the next pass to read its
     * conditions. A blank or unknown stat, or a zero or non-finite amount, is passed over. World thread.
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
            Entry entry = new Entry(key, index, one.modifier(), one.conditions());
            Entry old = before.get(key);
            // A modifier that stayed the same keeps its state; one that changed is written again by the next pass.
            if (old != null && old.sameAs(entry)) {
                entry.on = old.on;
            } else if (old != null && old.on && stats != null) {
                stats.removeModifier(old.stat, old.key);
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
    }

    /** Whether the entity at {@code ref} holds any bound modifier. */
    public static boolean has(@Nullable Ref<EntityStore> ref) {
        return ref != null && BY_REF.containsKey(ref);
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
        SWEPT.remove(ref);
    }

    /** Forget every entity (a test). */
    public static void clear() {
        BY_REF.clear();
        SWEPT.clear();
    }
}
