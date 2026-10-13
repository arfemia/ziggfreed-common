package com.ziggfreed.common.damage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.map.IndexedLookupTableAssetMap;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.DamageEntityInteraction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.combat.DamageCalculator;
import com.ziggfreed.common.util.SafeLog;

import it.unimi.dsi.fastutil.objects.Object2FloatMap;

/**
 * The read side behind {@link HitTags}: every loaded damage node's tags, keyed by the
 * {@link DamageCalculator} object the engine attaches to its hits.
 *
 * <p>The table is one immutable snapshot behind a volatile field, so a read on any world thread
 * takes no lock. It is rebuilt lazily, on the first read after {@link #markDirty} (an
 * {@code Interaction} or {@code EntityEffect} load or removal, or a new indexed key), under one lock;
 * a read on another thread while that rebuild runs waits for it rather than take the table being
 * replaced (a friendly bloom's check reads this), and a mark that lands while a rebuild runs makes
 * the next read rebuild again. A rebuild that throws logs once and leaves an empty table, so every
 * hit reads untagged until the next interaction or effect load.
 *
 * <p>A rebuild walks the interaction asset map by index ({@code getNextIndex} / {@code getAsset(int)},
 * the map's lock-free array read) and, for each {@link DamageEntityInteraction}, records its main
 * calculator and every angled and targeted one, all under the node's tags merged with each container
 * above it ({@code AssetExtraInfo.Data.getContainerData}). It then walks the entity-effect map the
 * same way and records each effect's {@code DamageCalculator} under the effect's own tags: the engine
 * attaches that calculator to every tick of the effect's damage over time ({@code ActiveEntityEffect}),
 * so a tagged effect's ticks read its tags. The merge and conflict rules are {@link HitTagMerge}'s.
 */
final class HitTagIndex {

    /** A cycle guard on the container climb; real nesting is a handful of levels. */
    private static final int MAX_CONTAINER_DEPTH = 64;

    private static final Object LOCK = new Object();

    /** The indexed keys, folded. Copy-on-write under {@link #LOCK}. */
    @Nonnull
    private static volatile Set<String> keys = Set.of(HitTagMerge.fold(HitTags.KEY));

    @Nonnull
    private static volatile Snapshot snapshot = Snapshot.EMPTY;

    private static volatile boolean dirty = true;

    /** True from the moment a rebuild clears {@link #dirty} until it publishes, so a read meanwhile waits. */
    private static volatile boolean rebuilding;

    private static final AtomicBoolean REBUILD_FAILURE_LOGGED = new AtomicBoolean();

    /** Each warning text logged once per process, so a rebuild per reload never repeats one. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private HitTagIndex() {
    }

    /** Adds a key to index (case-insensitive); a new key marks the table dirty. */
    static void indexKey(@Nonnull String tagKey) {
        String trimmed = tagKey.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        String folded = HitTagMerge.fold(trimmed);
        synchronized (LOCK) {
            if (keys.contains(folded)) {
                return;
            }
            Set<String> next = new HashSet<>(keys);
            next.add(folded);
            keys = Set.copyOf(next);
        }
        markDirty();
    }

    static void markDirty() {
        dirty = true;
    }

    /**
     * Marks the table dirty for a load of {@code loaded} entity effects only when one of them carries
     * an indexed tag key or was tagged in the published table, so a runtime effect written during play
     * (an untagged effect-grid bucket) costs no rebuild. A load that lands while a rebuild runs always
     * marks it, since the walk may already have passed that effect.
     */
    static void effectsLoaded(@Nonnull Map<String, EntityEffect> loaded) {
        if (dirty) {
            return;
        }
        if (rebuilding) {
            markDirty();
            return;
        }
        try {
            Set<String> tagged = snapshot.taggedEffects;
            Set<String> foldedKeys = keys;
            for (Map.Entry<String, EntityEffect> entry : loaded.entrySet()) {
                EntityEffect effect = entry.getValue();
                if (tagged.contains(entry.getKey()) || (effect != null && !HitTagMerge.merge(
                        rawTagChain(EntityEffect.CODEC.getData(effect)), foldedKeys).isEmpty())) {
                    markDirty();
                    return;
                }
            }
        } catch (Throwable t) {
            // An effect whose tags cannot be read: rebuild rather than risk a stale table.
            markDirty();
        }
    }

    /** Marks the table dirty when one of the {@code removed} entity effects was tagged in the published table. */
    static void effectsRemoved(@Nonnull Set<String> removed) {
        if (dirty) {
            return;
        }
        if (rebuilding) {
            markDirty();
            return;
        }
        try {
            Set<String> tagged = snapshot.taggedEffects;
            for (String id : removed) {
                if (tagged.contains(id)) {
                    markDirty();
                    return;
                }
            }
        } catch (Throwable t) {
            // A removal set that cannot be read: rebuild rather than risk a stale table.
            markDirty();
        }
    }

    /** The values under {@code tagKey} on the node that owns {@code calculator}; empty when none. */
    @Nonnull
    static List<String> values(@Nonnull DamageCalculator calculator, @Nonnull String tagKey) {
        String folded = indexedOrWarn(tagKey);
        if (folded == null) {
            return List.of();
        }
        Map<String, List<String>> tags = current().byCalculator.get(calculator);
        if (tags == null) {
            return List.of();
        }
        List<String> values = tags.get(folded);
        return values == null ? List.of() : values;
    }

    /** Every value any loaded interaction or entity effect declares under {@code tagKey}; empty when none. */
    @Nonnull
    static Set<String> declared(@Nonnull String tagKey) {
        String folded = indexedOrWarn(tagKey);
        if (folded == null) {
            return Set.of();
        }
        Set<String> values = current().declared.get(folded);
        return values == null ? Set.of() : values;
    }

    /** The folded key when it is indexed; otherwise warns once and answers null. */
    @Nullable
    private static String indexedOrWarn(@Nonnull String tagKey) {
        String folded = HitTagMerge.fold(tagKey.trim());
        if (keys.contains(folded)) {
            return folded;
        }
        warnOnce("[hit-tags] the tag key '" + tagKey + "' is not indexed, so it reads empty; call"
                + " HitTags.indexKey(\"" + tagKey + "\") at setup");
        return null;
    }

    @Nonnull
    private static Snapshot current() {
        // A rebuild in flight holds LOCK, so rebuild() here waits for it and then returns at once.
        if (dirty || rebuilding) {
            rebuild();
        }
        return snapshot;
    }

    private static void rebuild() {
        synchronized (LOCK) {
            if (!dirty) {
                return;
            }
            // Raised before dirty clears, so a reader that sees dirty cleared also sees this and waits.
            rebuilding = true;
            // Cleared before the walk, so a load that lands mid-walk marks it again and the next read rebuilds.
            dirty = false;
            try {
                Snapshot built;
                try {
                    built = build(keys);
                } catch (Throwable t) {
                    if (REBUILD_FAILURE_LOGGED.compareAndSet(false, true)) {
                        SafeLog.warn("[hit-tags] could not index the interactions' and effects' tags; every hit"
                                + " reads untagged until the next interaction or effect load", t);
                    }
                    built = Snapshot.EMPTY;
                }
                snapshot = built;
            } finally {
                rebuilding = false;
            }
        }
    }

    @Nonnull
    private static Snapshot build(@Nonnull Set<String> foldedKeys) {
        IndexedLookupTableAssetMap<String, Interaction> map = Interaction.getAssetMap();
        HitTagMerge.Table<DamageCalculator> table = new HitTagMerge.Table<>();
        Map<String, Set<String>> declared = new HashMap<>();
        int end = map.getNextIndex();
        for (int index = 0; index < end; index++) {
            Interaction interaction = map.getAsset(index);
            if (interaction == null) {
                continue;
            }
            Map<String, List<String>> tags = HitTagMerge.merge(
                    rawTagChain(Interaction.CODEC.getData(interaction)), foldedKeys);
            HitTagMerge.declare(declared, tags);
            if (interaction instanceof DamageEntityInteraction damage) {
                indexCalculators(table, damage, String.valueOf(interaction.getId()), tags);
            }
        }
        Set<String> taggedEffects = indexEffects(table, declared, foldedKeys);
        for (String warning : table.warnings()) {
            warnOnce(warning);
        }
        return new Snapshot(table.tagged(), HitTagMerge.freeze(declared), taggedEffects);
    }

    /**
     * Each entity effect's {@code DamageCalculator} under the effect's own tags; answers the ids of the
     * effects that carry an indexed key, for {@link #effectsLoaded} and {@link #effectsRemoved}. A child
     * effect that authors no {@code DamageCalculator} shares its parent's object, so a tagged child needs
     * its own (the shared-calculator rule {@code HitTagMerge.Table.put} warns about).
     */
    @Nonnull
    private static Set<String> indexEffects(@Nonnull HitTagMerge.Table<DamageCalculator> table,
                                            @Nonnull Map<String, Set<String>> declared,
                                            @Nonnull Set<String> foldedKeys) {
        IndexedLookupTableAssetMap<String, EntityEffect> map = EntityEffect.getAssetMap();
        Set<String> tagged = new HashSet<>();
        int end = map.getNextIndex();
        for (int index = 0; index < end; index++) {
            EntityEffect effect = map.getAsset(index);
            if (effect == null) {
                continue;
            }
            Map<String, List<String>> tags = HitTagMerge.merge(
                    rawTagChain(EntityEffect.CODEC.getData(effect)), foldedKeys);
            HitTagMerge.declare(declared, tags);
            put(table, effect.getDamageCalculator(), effect.getId() + " (EntityEffect)", tags);
            if (!tags.isEmpty()) {
                tagged.add(effect.getId());
            }
        }
        return Set.copyOf(tagged);
    }

    /** The asset's raw tags, then each container's above it, nearest first. */
    @Nonnull
    private static List<Map<String, String[]>> rawTagChain(@Nullable AssetExtraInfo.Data first) {
        List<Map<String, String[]>> chain = new ArrayList<>(4);
        AssetExtraInfo.Data data = first;
        for (int depth = 0; data != null && depth < MAX_CONTAINER_DEPTH; depth++) {
            chain.add(data.getRawTags());
            data = data.getContainerData();
        }
        return chain;
    }

    /** The node's main calculator plus every angled and targeted one, each under the node's tags. */
    private static void indexCalculators(@Nonnull HitTagMerge.Table<DamageCalculator> table,
                                         @Nonnull DamageEntityInteraction damage, @Nonnull String id,
                                         @Nonnull Map<String, List<String>> tags) {
        put(table, damage.getDamageCalculator(), id, tags);
        DamageEntityInteraction.AngledDamage[] angled = damage.getAngledDamage();
        if (angled != null) {
            for (int i = 0; i < angled.length; i++) {
                if (angled[i] != null) {
                    put(table, angled[i].getDamageCalculator(), id + " (AngledDamage " + i + ")", tags);
                }
            }
        }
        Map<String, DamageEntityInteraction.TargetedDamage> targeted = damage.getTargetedDamage();
        if (targeted != null) {
            for (Map.Entry<String, DamageEntityInteraction.TargetedDamage> entry : targeted.entrySet()) {
                if (entry.getValue() != null) {
                    put(table, entry.getValue().getDamageCalculator(),
                            id + " (TargetedDamage " + entry.getKey() + ")", tags);
                }
            }
        }
    }

    private static void put(@Nonnull HitTagMerge.Table<DamageCalculator> table, @Nullable DamageCalculator calculator,
                            @Nonnull String owner, @Nonnull Map<String, List<String>> tags) {
        if (calculator != null) {
            table.put(calculator, owner, tags, tags.isEmpty() ? 0 : damageTypeCount(calculator));
        }
    }

    /**
     * How many damage types the calculator names: the size of what it deals, the engine queueing one
     * hit per type ({@code DamageCalculatorSystems.queueDamageCalculator}). The calculator exposes no
     * getter for its {@code BaseDamage} map, so this asks it to calculate once (a zero duration; the
     * numbers are thrown away).
     */
    private static int damageTypeCount(@Nonnull DamageCalculator calculator) {
        Object2FloatMap<DamageCause> dealt = calculator.calculateDamage(0.0);
        return dealt == null ? 0 : dealt.size();
    }

    private static void warnOnce(@Nonnull String message) {
        if (WARNED.add(message)) {
            SafeLog.warn(message);
        }
    }

    /**
     * One published table: calculator to its tags (folded key to values), folded key to every declared
     * value, and the ids of the entity effects that carry an indexed key.
     */
    private static final class Snapshot {

        static final Snapshot EMPTY = new Snapshot(new IdentityHashMap<>(), Map.of(), Set.of());

        /** Never mutated once published. */
        @Nonnull
        final IdentityHashMap<DamageCalculator, Map<String, List<String>>> byCalculator;
        @Nonnull
        final Map<String, Set<String>> declared;
        @Nonnull
        final Set<String> taggedEffects;

        Snapshot(@Nonnull IdentityHashMap<DamageCalculator, Map<String, List<String>>> byCalculator,
                 @Nonnull Map<String, Set<String>> declared, @Nonnull Set<String> taggedEffects) {
            this.byCalculator = byCalculator;
            this.declared = declared;
            this.taggedEffects = taggedEffects;
        }
    }
}
