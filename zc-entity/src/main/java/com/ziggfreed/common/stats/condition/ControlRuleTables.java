package com.ziggfreed.common.stats.condition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.map.AssetMapWithIndexes;
import com.hypixel.hytale.assetstore.map.IndexedLookupTableAssetMap;
import com.hypixel.hytale.builtin.tagset.config.NPCGroup;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.ziggfreed.common.util.SafeLog;

/**
 * The rule tags {@link BossRules} reads, as two immutable snapshots rebuilt on the first read after a mark: the NPC
 * groups (the family group's rule, every other tagged group's rule, the groups {@value BossRules#RANK_TAG} counts as
 * bosses or elites), marked on every group load or removal; and the effects (each effect index's rule), marked on an
 * effect load or removal touching a tagged or already known rule effect, so the runtime effects a grid writes during
 * play cost no rebuild. A role's groups are read live off the engine's own group sets, so a role load needs no
 * rebuild. A value naming no kind or rank is warned once per asset and value. A rebuild that throws logs once and
 * leaves an empty snapshot until the next mark.
 */
final class ControlRuleTables {

    private static final Object LOCK = new Object();

    private static final AtomicBoolean BUILD_FAILURE_LOGGED = new AtomicBoolean();

    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private static final String[] RANKS = {"Boss", "Elite"};

    /**
     * The groups: the family group's index ({@link AssetMapWithIndexes#NOT_FOUND} when it is not loaded) and rule,
     * each other tagged group's index and rule, the ranked groups' indexes, and the masks of every kind any group
     * names and shrugs.
     */
    record Groups(int familyIndex, @Nonnull ControlRule family, @Nonnull int[] tagged,
            @Nonnull ControlRule[] taggedRules, @Nonnull int[] ranked, int named, int shrugs) {
        static final Groups EMPTY = new Groups(AssetMapWithIndexes.NOT_FOUND, ControlRule.NONE, new int[0],
                new ControlRule[0], new int[0], 0, 0);
    }

    /**
     * The effects: by effect index its rule (null for none), every rule effect's id (lower-cased), and the masks of
     * every kind any effect names and shrugs.
     */
    record Effects(@Nonnull ControlRule[] byIndex, @Nonnull Set<String> ids, int named, int shrugs) {
        static final Effects EMPTY = new Effects(new ControlRule[0], Set.of(), 0, 0);

        boolean any() {
            return !ids.isEmpty();
        }

        @Nullable
        ControlRule at(int index) {
            return index >= 0 && index < byIndex.length ? byIndex[index] : null;
        }
    }

    @Nonnull
    private static volatile Groups groups = Groups.EMPTY;
    private static volatile boolean groupsDirty = true;

    @Nonnull
    private static volatile Effects effects = Effects.EMPTY;
    private static volatile boolean effectsDirty = true;

    private ControlRuleTables() {
    }

    /** A group load or removal: the groups are read again on their next use. */
    static void groupsChanged() {
        groupsDirty = true;
    }

    /** Marks the effects when one of {@code loaded} carries a rule tag or was a rule effect before. */
    static void effectsLoaded(@Nonnull Map<String, EntityEffect> loaded) {
        if (effectsDirty) {
            return;
        }
        try {
            Set<String> known = effects.ids();
            for (Map.Entry<String, EntityEffect> entry : loaded.entrySet()) {
                String id = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
                if (known.contains(id) || carriesRule(entry.getValue())) {
                    effectsDirty = true;
                    return;
                }
            }
        } catch (Throwable t) {
            effectsDirty = true;
        }
    }

    /** Marks the effects when one of {@code removed} was a rule effect. */
    static void effectsRemoved(@Nonnull Set<String> removed) {
        if (effectsDirty) {
            return;
        }
        Set<String> known = effects.ids();
        for (String id : removed) {
            if (id != null && known.contains(id.toLowerCase(Locale.ROOT))) {
                effectsDirty = true;
                return;
            }
        }
    }

    @Nonnull
    static Groups groups() {
        if (groupsDirty) {
            rebuildGroups();
        }
        return groups;
    }

    @Nonnull
    static Effects effects() {
        if (effectsDirty) {
            rebuildEffects();
        }
        return effects;
    }

    private static void rebuildGroups() {
        synchronized (LOCK) {
            if (!groupsDirty) {
                return;
            }
            // Cleared before the walk, so a load landing mid-walk marks it again and the next read rebuilds.
            groupsDirty = false;
            try {
                groups = buildGroups();
            } catch (Throwable t) {
                logFailure(t);
                groups = Groups.EMPTY;
            }
        }
    }

    private static void rebuildEffects() {
        synchronized (LOCK) {
            if (!effectsDirty) {
                return;
            }
            effectsDirty = false;
            try {
                effects = buildEffects();
            } catch (Throwable t) {
                logFailure(t);
                effects = Effects.EMPTY;
            }
        }
    }

    @Nonnull
    private static Groups buildGroups() {
        IndexedLookupTableAssetMap<String, NPCGroup> map = NPCGroup.getAssetMap();
        int familyIndex = AssetMapWithIndexes.NOT_FOUND;
        ControlRule family = ControlRule.NONE;
        List<Integer> tagged = new ArrayList<>();
        List<ControlRule> taggedRules = new ArrayList<>();
        List<Integer> ranked = new ArrayList<>();
        int named = 0;
        int shrugs = 0;
        // By index, through the map's own locked reads, as the effects are walked: a group load mid-walk marks again.
        int end = Math.max(0, map.getNextIndex());
        for (int index = 0; index < end; index++) {
            NPCGroup group = map.getAsset(index);
            String id = group == null ? null : group.getId();
            if (id == null) {
                continue;
            }
            Map<String, String[]> raw = rawTags(NPCGroup.CODEC.getData(group));
            ControlRule rule = ControlRule.parse(raw,
                    value -> warnOnce("NPC group", id, value, "kind of control", ControlKind.ids()));
            if (id.equalsIgnoreCase(BossRules.FAMILY_GROUP)) {
                familyIndex = index;
                family = rule;
            } else {
                if (!rule.isEmpty()) {
                    tagged.add(index);
                    taggedRules.add(rule);
                }
                if (ControlRule.ranked(raw, value -> warnOnce("NPC group", id, value, "rank", RANKS))) {
                    ranked.add(index);
                }
            }
            named |= rule.named();
            shrugs |= rule.shrugs();
        }
        return new Groups(familyIndex, family, ints(tagged), taggedRules.toArray(new ControlRule[0]), ints(ranked),
                named, shrugs);
    }

    @Nonnull
    private static Effects buildEffects() {
        IndexedLookupTableAssetMap<String, EntityEffect> map = EntityEffect.getAssetMap();
        int end = Math.max(0, map.getNextIndex());
        ControlRule[] byIndex = null;
        Set<String> ids = new HashSet<>();
        int named = 0;
        int shrugs = 0;
        for (int index = 0; index < end; index++) {
            EntityEffect effect = map.getAsset(index);
            if (effect == null || effect.getId() == null) {
                continue;
            }
            String id = effect.getId();
            ControlRule rule = ControlRule.parse(rawTags(EntityEffect.CODEC.getData(effect)),
                    value -> warnOnce("effect", id, value, "kind of control", ControlKind.ids()));
            if (rule.isEmpty()) {
                continue;
            }
            if (byIndex == null) {
                byIndex = new ControlRule[end];
            }
            byIndex[index] = rule;
            ids.add(id.toLowerCase(Locale.ROOT));
            named |= rule.named();
            shrugs |= rule.shrugs();
        }
        return byIndex == null ? Effects.EMPTY : new Effects(byIndex, Set.copyOf(ids), named, shrugs);
    }

    /** Does {@code effect}'s own native Tags carry a rule tag (a value that names no kind still counts)? */
    private static boolean carriesRule(@Nullable EntityEffect effect) {
        if (effect == null) {
            return false;
        }
        Map<String, String[]> raw = rawTags(EntityEffect.CODEC.getData(effect));
        if (raw == null) {
            return false;
        }
        for (String key : raw.keySet()) {
            if (key != null && (key.equalsIgnoreCase(BossRules.LANDS_TAG) || key.equalsIgnoreCase(BossRules.SHRUGS_TAG))) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static Map<String, String[]> rawTags(@Nullable AssetExtraInfo.Data data) {
        return data == null ? null : data.getRawTags();
    }

    private static void warnOnce(@Nonnull String type, @Nonnull String id, @Nonnull String value, @Nonnull String what,
            @Nonnull String[] known) {
        if (WARNED.add(type + "|" + id.toLowerCase(Locale.ROOT) + "|" + value.toLowerCase(Locale.ROOT))) {
            SafeLog.warn("[control] the " + type + " '" + id + "' names '" + value + "', which is no " + what + " ("
                    + String.join(", ", known) + "): it counts nothing");
        }
    }

    private static void logFailure(@Nonnull Throwable t) {
        if (BUILD_FAILURE_LOGGED.compareAndSet(false, true)) {
            SafeLog.warn("[control] could not read the crowd-control rule tags; none counts until the next group or "
                    + "effect load", t);
        }
    }

    @Nonnull
    private static int[] ints(@Nonnull List<Integer> values) {
        int[] out = new int[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }
}
