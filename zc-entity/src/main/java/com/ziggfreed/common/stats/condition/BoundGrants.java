package com.ziggfreed.common.stats.condition;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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
 * Bound grants (R933): stat amounts that count only while their conditions hold on their holder, a talent bound
 * {@code While} its weapon is in hand. A writer hands an entity's bound amounts here per owner ({@link #set}); the
 * conditional holder pass ({@code ConditionalStatSystems.HolderPass}, a few times a second for a player) reads each
 * one's conditions and puts its additive modifier on the stat's maximum under {@value #MODIFIER_PREFIX}{@code <id>}, or
 * takes it off, only on a change, so every reader of the stat (a hit's crit step, a page) sees it as one more modifier.
 *
 * <p>The key sits under the track grants' owner behind an {@code @}, like the conditional fold's, so the stat grant
 * writer leaves it alone and the 1.7.x rollback sweep of {@code zigtrack:} keys clears it (R755). The modifiers are
 * saved with the entity like any other, so an entity's first pass sweeps every one it finds that is not wanted now.
 * In memory only, keyed by the entity's reference: a holder that leaves its store is forgotten, and its next grant
 * rebuild (a player's ready settle) hands its amounts over again. World thread.
 */
public final class BoundGrants {

    /** The prefix of every modifier key this writes. */
    public static final String MODIFIER_PREFIX = "zigtrack:@bound:";

    /**
     * One bound amount: {@code id} is unique among its owner's (the grant's own key), {@code statId} the stat it
     * raises, {@code amount} what it adds while every one of {@code conditions} holds.
     */
    public record Bound(@Nonnull String id, @Nonnull String statId, double amount, @Nonnull Condition[] conditions) {
    }

    /** What one bound amount resolved to on a stat map, and whether its modifier is on now. */
    private static final class Entry {
        final String key;
        final int stat;
        final float amount;
        final Condition[] conditions;
        boolean on;

        Entry(@Nonnull String key, int stat, float amount, @Nonnull Condition[] conditions) {
            this.key = key;
            this.stat = stat;
            this.amount = amount;
            this.conditions = conditions;
        }
    }

    /** Each entity's bound amounts by owner, then by modifier key. */
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
     * Make {@code owner}'s bound amounts on the entity at {@code ref} exactly {@code bound}, replacing what it handed
     * over before: an amount no longer listed has its modifier taken off at once, a new one waits for the next pass
     * to read its conditions. A blank stat, an unknown stat, a zero or non-finite amount is passed over. World thread.
     */
    public static void set(@Nullable EntityStatMap stats, @Nonnull Ref<EntityStore> ref, @Nonnull String owner,
            @Nonnull List<Bound> bound) {
        Map<String, Map<String, Entry>> owners = BY_REF.computeIfAbsent(ref, r -> new ConcurrentHashMap<>());
        Map<String, Entry> before = owners.getOrDefault(owner, Map.of());
        Map<String, Entry> now = new HashMap<>();
        for (Bound one : bound) {
            if (one.statId().isBlank() || !Double.isFinite(one.amount()) || one.amount() == 0.0) {
                continue;
            }
            int index = StatIndexCache.resolve(one.statId());
            if (index == StatIndexCache.UNRESOLVED) {
                StatIndexCache.warnUnknownOnce(one.statId());
                continue;
            }
            String key = MODIFIER_PREFIX + owner + ":" + one.id();
            Entry entry = new Entry(key, index, (float) one.amount(), one.conditions());
            Entry old = before.get(key);
            // An amount that stayed the same keeps its state; one that changed is written again by the next pass.
            if (old != null && old.stat == entry.stat && old.amount == entry.amount) {
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
        } else {
            owners.put(owner, now);
        }
    }

    /** Whether the entity at {@code ref} holds any bound amount. */
    public static boolean has(@Nullable Ref<EntityStore> ref) {
        return ref != null && BY_REF.containsKey(ref);
    }

    /**
     * Read every bound amount's conditions on the entity at {@code ref} and put or take off its modifier where that
     * changed; the entity's first pass also sweeps every saved {@value #MODIFIER_PREFIX} modifier not wanted now.
     * Never throws.
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
        for (Map<String, Entry> entries : owners.values()) {
            for (Entry entry : entries.values()) {
                boolean holds;
                try {
                    holds = Nearby.allHold(accessor, ref, entry.conditions, now);
                } catch (Throwable t) {
                    holds = false;
                }
                if (holds == entry.on) {
                    continue;
                }
                if (holds) {
                    stats.putModifier(entry.stat, entry.key, new StaticModifier(Modifier.ModifierTarget.MAX,
                            StaticModifier.CalculationType.ADDITIVE, entry.amount));
                } else {
                    stats.removeModifier(entry.stat, entry.key);
                }
                entry.on = holds;
            }
        }
    }

    /** Take off every saved bound modifier on {@code stats} that none of {@code owners} wants. */
    private static void sweep(@Nonnull EntityStatMap stats, @Nullable Map<String, Map<String, Entry>> owners) {
        for (int i = 0; i < stats.size(); i++) {
            EntityStatValue value = stats.get(i);
            Map<String, Modifier> modifiers = value == null ? null : value.getModifiers();
            if (modifiers == null || modifiers.isEmpty()) {
                continue;
            }
            for (String key : new ArrayList<>(modifiers.keySet())) {
                if (writesKey(key) && !wanted(owners, key)) {
                    stats.removeModifier(i, key);
                }
            }
        }
        // A wanted key found saved is written again on this pass, whatever it held.
        if (owners != null) {
            for (Map<String, Entry> entries : owners.values()) {
                for (Entry entry : entries.values()) {
                    stats.removeModifier(entry.stat, entry.key);
                    entry.on = false;
                }
            }
        }
    }

    private static boolean wanted(@Nullable Map<String, Map<String, Entry>> owners, @Nonnull String key) {
        if (owners == null) {
            return false;
        }
        for (Map<String, Entry> entries : owners.values()) {
            if (entries.containsKey(key)) {
                return true;
            }
        }
        return false;
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
