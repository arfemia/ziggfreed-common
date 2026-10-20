package com.ziggfreed.common.stats.condition;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.map.IndexedLookupTableAssetMap;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.effect.ActiveEntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * Which effects are in which family ({@link EffectFamilyAsset}), as a table by effect index: every effect a family
 * lists, every effect whose own native {@code Tags} carry {@value EffectFamilyAsset#TAG} naming the family (a
 * {@code Parent}'s tags are its child's too), and every member of every family it includes.
 *
 * <p>One immutable snapshot behind a volatile field, rebuilt on the first read after it is marked (a family fold, or
 * an effect load or removal touching a tagged, listed or already classified effect), so the runtime effects a grid
 * writes during play cost no rebuild. A rebuild that throws logs once and leaves an empty table until the next mark.
 */
public final class EffectFamilies {

    private static final Object LOCK = new Object();

    private static final AtomicBoolean BUILD_FAILURE_LOGGED = new AtomicBoolean();

    /** One family's duration rule over an effect: the owner's stat (seconds added), the carrier's (percent off). */
    public record DurationRule(@Nonnull String family, @Nullable String addedBy, @Nullable String cutBy,
            double cutCap) {
    }

    /**
     * The table: each family's member indexes, every effect id it classified (lower-cased), and each effect index's
     * duration rules.
     */
    private record Table(@Nonnull Map<String, BitSet> members, @Nonnull Set<String> ids,
            @Nonnull Map<Integer, List<DurationRule>> durations) {
        static final Table EMPTY = new Table(Map.of(), Set.of(), Map.of());
    }

    @Nonnull
    private static volatile Table table = Table.EMPTY;

    private static volatile boolean dirty = true;

    private EffectFamilies() {
    }

    /** Drop the table so the next read rebuilds it. */
    public static void markDirty() {
        dirty = true;
    }

    /** Marks the table when one of {@code loaded} carries the tag, is listed, or was classified before. */
    public static void effectsLoaded(@Nonnull Map<String, EntityEffect> loaded) {
        if (dirty) {
            return;
        }
        try {
            Set<String> known = table.ids();
            Set<String> listed = listedIds();
            for (Map.Entry<String, EntityEffect> entry : loaded.entrySet()) {
                String id = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
                if (known.contains(id) || listed.contains(id) || !tagged(entry.getValue()).isEmpty()) {
                    markDirty();
                    return;
                }
            }
        } catch (Throwable t) {
            markDirty();
        }
    }

    /** Marks the table when one of {@code removed} was classified. */
    public static void effectsRemoved(@Nonnull Set<String> removed) {
        if (dirty) {
            return;
        }
        Set<String> known = table.ids();
        for (String id : removed) {
            if (id != null && known.contains(id.toLowerCase(Locale.ROOT))) {
                markDirty();
                return;
            }
        }
    }

    /** Is the effect at {@code effectIndex} in {@code family} (any case)? False for an unknown family. */
    public static boolean contains(@Nullable String family, int effectIndex) {
        if (family == null || effectIndex < 0) {
            return false;
        }
        BitSet members = current().members().get(family.toLowerCase(Locale.ROOT));
        return members != null && members.get(effectIndex);
    }

    /** The duration rules of every family the effect at {@code effectIndex} is in; empty for none. */
    @Nonnull
    public static List<DurationRule> durationRules(int effectIndex) {
        List<DurationRule> rules = current().durations().get(effectIndex);
        return rules == null ? List.of() : rules;
    }

    /** Does any family carry a duration rule at all (so an effect pass has anything to do)? */
    public static boolean anyDurationRule() {
        return !current().durations().isEmpty();
    }

    /** Does {@code ref} carry an active effect of {@code family}? Never throws. */
    public static boolean carries(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref,
            @Nullable String family) {
        return carriesFrom(accessor, ref, family, null);
    }

    /**
     * Does {@code ref} carry an active effect of {@code family} that {@code owner} put on it (any owner when null)?
     * Never throws.
     */
    public static boolean carriesFrom(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref,
            @Nullable String family, @Nullable Ref<EntityStore> owner) {
        if (family == null || !ref.isValid()) {
            return false;
        }
        try {
            EffectControllerComponent ctrl = accessor.getComponent(ref, EffectControllerComponent.getComponentType());
            if (ctrl == null || ctrl.getActiveEffects().isEmpty()) {
                return false;
            }
            BitSet members = current().members().get(family.toLowerCase(Locale.ROOT));
            if (members == null || members.isEmpty()) {
                return false;
            }
            for (ActiveEntityEffect active : ctrl.getActiveEffects().values()) {
                if (members.get(active.getEntityEffectIndex())
                        && (owner == null || Nearby.Targets.same(owner, active.getOwner()))) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Each family's members as effect ids, every include folded in: {@code listed} is each family's own list and
     * includes, {@code tagged} each effect's tagged families (both lower-cased). Pure, so the rule is tested without
     * an engine. A loop or a family naming itself counts each member once.
     */
    @Nonnull
    public static Map<String, Set<String>> fold(@Nonnull Map<String, EffectFamilyAsset> listed,
            @Nonnull Map<String, Set<String>> tagged) {
        Map<String, Set<String>> own = new LinkedHashMap<>();
        Map<String, Set<String>> includes = new LinkedHashMap<>();
        for (Map.Entry<String, EffectFamilyAsset> entry : listed.entrySet()) {
            String family = entry.getKey().toLowerCase(Locale.ROOT);
            own.computeIfAbsent(family, k -> new LinkedHashSet<>()).addAll(entry.getValue().effectIds());
            includes.computeIfAbsent(family, k -> new LinkedHashSet<>()).addAll(entry.getValue().includedFamilies());
        }
        for (Map.Entry<String, Set<String>> entry : tagged.entrySet()) {
            for (String family : entry.getValue()) {
                own.computeIfAbsent(family.toLowerCase(Locale.ROOT), k -> new LinkedHashSet<>()).add(entry.getKey());
            }
        }
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (String family : own.keySet()) {
            Set<String> members = new LinkedHashSet<>();
            Set<String> seen = new HashSet<>();
            Deque<String> walk = new ArrayDeque<>();
            walk.add(family);
            while (!walk.isEmpty()) {
                String next = walk.poll();
                if (!seen.add(next)) {
                    continue;
                }
                members.addAll(own.getOrDefault(next, Set.of()));
                walk.addAll(includes.getOrDefault(next, Set.of()));
            }
            out.put(family, Set.copyOf(members));
        }
        return out;
    }

    @Nonnull
    private static Table current() {
        if (dirty) {
            rebuild();
        }
        return table;
    }

    private static void rebuild() {
        synchronized (LOCK) {
            if (!dirty) {
                return;
            }
            // Cleared before the walk, so a load landing mid-walk marks it again and the next read rebuilds.
            dirty = false;
            try {
                table = build();
            } catch (Throwable t) {
                if (BUILD_FAILURE_LOGGED.compareAndSet(false, true)) {
                    SafeLog.warn("[condition] could not read which effects are in which family; no effect counts in"
                            + " any family until the next effect load", t);
                }
                table = Table.EMPTY;
            }
        }
    }

    @Nonnull
    private static Table build() {
        IndexedLookupTableAssetMap<String, EntityEffect> map = EntityEffect.getAssetMap();
        Map<String, Integer> indexById = new HashMap<>();
        Map<String, Set<String>> tagged = new HashMap<>();
        int end = map.getNextIndex();
        for (int index = 0; index < end; index++) {
            EntityEffect effect = map.getAsset(index);
            if (effect == null || effect.getId() == null) {
                continue;
            }
            String id = effect.getId().toLowerCase(Locale.ROOT);
            indexById.put(id, index);
            Set<String> families = tagged(effect);
            if (!families.isEmpty()) {
                tagged.put(id, families);
            }
        }
        Map<String, EffectFamilyAsset> families = EffectFamilyConfig.getInstance().all();
        Map<String, Set<String>> folded = fold(families, tagged);
        Map<String, BitSet> members = new HashMap<>();
        Set<String> ids = new HashSet<>();
        Map<Integer, List<DurationRule>> durations = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : folded.entrySet()) {
            BitSet bits = new BitSet(Math.max(0, end));
            EffectFamilyAsset family = families.get(entry.getKey());
            EffectFamilyAsset.Duration duration = family == null ? null : family.duration();
            DurationRule rule = duration == null || (duration.addedBy() == null && duration.cutBy() == null) ? null
                    : new DurationRule(entry.getKey(), duration.addedBy(), duration.cutBy(), duration.cutCap());
            for (String id : entry.getValue()) {
                Integer index = indexById.get(id);
                if (index != null) {
                    bits.set(index);
                    ids.add(id);
                    if (rule != null) {
                        durations.computeIfAbsent(index, k -> new ArrayList<>()).add(rule);
                    }
                }
            }
            members.put(entry.getKey(), bits);
        }
        Map<Integer, List<DurationRule>> frozen = new HashMap<>();
        durations.forEach((index, rules) -> frozen.put(index, List.copyOf(rules)));
        return new Table(Map.copyOf(members), Set.copyOf(ids), Map.copyOf(frozen));
    }

    /** Every effect id any family lists, lower-cased. */
    @Nonnull
    private static Set<String> listedIds() {
        Set<String> out = new HashSet<>();
        for (EffectFamilyAsset family : EffectFamilyConfig.getInstance().all().values()) {
            out.addAll(family.effectIds());
        }
        return out;
    }

    /** The families an effect's own native Tags name under {@value EffectFamilyAsset#TAG}, lower-cased. */
    @Nonnull
    private static Set<String> tagged(@Nullable EntityEffect effect) {
        if (effect == null) {
            return Set.of();
        }
        AssetExtraInfo.Data data = EntityEffect.CODEC.getData(effect);
        Map<String, String[]> raw = data == null ? null : data.getRawTags();
        if (raw == null) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, String[]> entry : raw.entrySet()) {
            if (entry.getKey() == null || !entry.getKey().equalsIgnoreCase(EffectFamilyAsset.TAG)
                    || entry.getValue() == null) {
                continue;
            }
            for (String value : entry.getValue()) {
                if (value != null && !value.isBlank()) {
                    out.add(value.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }
}
