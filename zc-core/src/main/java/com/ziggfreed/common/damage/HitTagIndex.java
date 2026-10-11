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
 * {@code Interaction} load or removal, or a new indexed key), under one lock; a mark that lands
 * while a rebuild runs makes the next read rebuild again. A rebuild that throws logs once and
 * leaves an empty table, so every hit reads untagged until the next interaction load.
 *
 * <p>A rebuild walks the interaction asset map by index ({@code getNextIndex} / {@code getAsset(int)},
 * the map's lock-free array read) and, for each {@link DamageEntityInteraction}, records its main
 * calculator and every angled and targeted one, all under the node's tags merged with each container
 * above it ({@code AssetExtraInfo.Data.getContainerData}). The merge and conflict rules are
 * {@link HitTagMerge}'s.
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

    /** Every value any loaded interaction declares under {@code tagKey}; empty when none. */
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
        if (dirty) {
            rebuild();
        }
        return snapshot;
    }

    private static void rebuild() {
        synchronized (LOCK) {
            if (!dirty) {
                return;
            }
            // Cleared before the walk, so a load that lands mid-walk marks it again and the next read rebuilds.
            dirty = false;
            Snapshot built;
            try {
                built = build(keys);
            } catch (Throwable t) {
                if (REBUILD_FAILURE_LOGGED.compareAndSet(false, true)) {
                    SafeLog.warn("[hit-tags] could not index the interactions' tags; every hit reads untagged"
                            + " until the next interaction load", t);
                }
                built = Snapshot.EMPTY;
            }
            snapshot = built;
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
            Map<String, List<String>> tags = HitTagMerge.merge(rawTagChain(interaction), foldedKeys);
            HitTagMerge.declare(declared, tags);
            if (interaction instanceof DamageEntityInteraction damage) {
                indexCalculators(table, damage, String.valueOf(interaction.getId()), tags);
            }
        }
        for (String warning : table.warnings()) {
            warnOnce(warning);
        }
        return new Snapshot(table.tagged(), HitTagMerge.freeze(declared));
    }

    /** The node's raw tags, then each container's above it, nearest first. */
    @Nonnull
    private static List<Map<String, String[]>> rawTagChain(@Nonnull Interaction interaction) {
        List<Map<String, String[]>> chain = new ArrayList<>(4);
        AssetExtraInfo.Data data = Interaction.CODEC.getData(interaction);
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

    /** One published table: calculator to its tags (folded key to values), and folded key to every declared value. */
    private static final class Snapshot {

        static final Snapshot EMPTY = new Snapshot(new IdentityHashMap<>(), Map.of());

        /** Never mutated once published. */
        @Nonnull
        final IdentityHashMap<DamageCalculator, Map<String, List<String>>> byCalculator;
        @Nonnull
        final Map<String, Set<String>> declared;

        Snapshot(@Nonnull IdentityHashMap<DamageCalculator, Map<String, List<String>>> byCalculator,
                 @Nonnull Map<String, Set<String>> declared) {
            this.byCalculator = byCalculator;
            this.declared = declared;
        }
    }
}
