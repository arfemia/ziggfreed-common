package com.ziggfreed.common.effect.control;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.map.IndexedLookupTableAssetMap;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.ziggfreed.common.util.SafeLog;

/**
 * Which effects make their wearer shed the game's own stuns: an effect tagged {@value StunControl#STUN_IMMUNE_TAG} names,
 * as the tag's values, the effects a wearer never keeps ({@code "Tags": { "ZigStunImmune": ["Stun", "Root"] }}: the
 * game's stun bomb and root wand). {@link StunImmunitySystem} takes any of them off a wearer the tick it lands, so a
 * stun that never asks {@link StunControl#isImmune} (the game's own, which asks only for {@code Immune}) does not hold
 * it either. Only the ids named are shed, never their children, so the library's {@code Zig_Stunned} (a child of
 * {@code Stun}) and a fight's own stun effects stay; a value that names no effect sheds nothing. Read from the effect
 * files as they load; nothing else is kept.
 */
public final class StunImmunity {

    private static final Object LOCK = new Object();
    private static final AtomicBoolean BUILD_FAILURE_LOGGED = new AtomicBoolean();

    /** By effect index: the effect indexes its wearer sheds, or null for an effect that sheds none. */
    @Nonnull
    private static volatile int[][] table = new int[0][];

    private static volatile boolean dirty = true;

    private StunImmunity() {
    }

    /** An effect load or removal: the table is read again on its next use. */
    static void markDirty() {
        dirty = true;
    }

    /** Whether any loaded effect sheds anything: a server with none reads no entity's effects. */
    static boolean any() {
        return current().length > 0;
    }

    /** The effect indexes a wearer of the effect at {@code index} sheds, or null. */
    @Nullable
    static int[] shedBy(int index) {
        int[][] now = current();
        return index >= 0 && index < now.length ? now[index] : null;
    }

    /**
     * The effect ids an effect's raw native tags name for its wearer to shed: the {@value StunControl#STUN_IMMUNE_TAG}
     * tag's values, trimmed, blanks dropped, the key matched without regard to case. Empty when it carries no such tag.
     * Pure over the tags.
     */
    @Nonnull
    public static List<String> shedIds(@Nullable Map<String, String[]> rawTags) {
        List<String> out = new ArrayList<>();
        if (rawTags == null) {
            return out;
        }
        String key = StunControl.STUN_IMMUNE_TAG.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String[]> entry : rawTags.entrySet()) {
            if (entry.getKey() == null || !entry.getKey().toLowerCase(Locale.ROOT).equals(key) || entry.getValue() == null) {
                continue;
            }
            for (String value : entry.getValue()) {
                if (value != null && !value.isBlank() && !out.contains(value.trim())) {
                    out.add(value.trim());
                }
            }
        }
        return out;
    }

    @Nonnull
    private static int[][] current() {
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
            dirty = false;
            try {
                table = build();
            } catch (Throwable t) {
                if (BUILD_FAILURE_LOGGED.compareAndSet(false, true)) {
                    SafeLog.warn("[control] could not read which effects shed the game's stuns; none does until the "
                            + "next effect load", t);
                }
                table = new int[0][];
            }
        }
    }

    @Nonnull
    private static int[][] build() {
        IndexedLookupTableAssetMap<String, EntityEffect> map = EntityEffect.getAssetMap();
        int end = Math.max(0, map.getNextIndex());
        Map<Integer, int[]> found = new HashMap<>();
        for (int index = 0; index < end; index++) {
            EntityEffect effect = map.getAsset(index);
            if (effect == null) {
                continue;
            }
            AssetExtraInfo.Data data = EntityEffect.CODEC.getData(effect);
            List<String> ids = shedIds(data == null ? null : data.getRawTags());
            List<Integer> shed = new ArrayList<>();
            for (String id : ids) {
                int target = map.getIndex(id);
                if (target >= 0 && target != index && !shed.contains(target)) {
                    shed.add(target);
                }
            }
            if (!shed.isEmpty()) {
                found.put(index, shed.stream().mapToInt(Integer::intValue).toArray());
            }
        }
        if (found.isEmpty()) {
            return new int[0][];
        }
        int[][] out = new int[end][];
        for (Map.Entry<Integer, int[]> entry : found.entrySet()) {
            out[entry.getKey()] = entry.getValue();
        }
        return out;
    }
}
