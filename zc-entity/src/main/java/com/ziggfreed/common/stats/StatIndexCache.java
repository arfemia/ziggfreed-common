package com.ziggfreed.common.stats;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.ziggfreed.common.util.SafeLog;

/**
 * Resolves a native {@link EntityStatType} id to its engine asset-map index and memoizes the
 * result. Shared by every class that needs to turn a stat id string into an index
 * ({@link EquipStatBridge}, {@link StatMirror}, {@link StatChannelAudit}, and the library's own
 * {@code hytale:stat} factor provider) so the resolution + caching rule exists exactly once.
 *
 * <p><b>Deliberately NOT routed through {@code util.AssetIndexCache}</b> even though the package
 * router lists it as the one allowed cross-domain primitive: that cache treats index {@code 0} as
 * "not resolved yet" (several engine asset maps reserve slot 0 as a none/clear sentinel), but a
 * custom {@link EntityStatType} can legitimately land at index 0 depending on pack load order -
 * caching only {@code idx > 0} would permanently mis-resolve such a channel as unregistered. The
 * rule here is to cache any {@code idx >= 0} (the engine's real "missing" sentinel is
 * {@code Integer.MIN_VALUE}) and never cache a miss, so a not-yet-loaded pack channel re-resolves
 * on the next call.
 */
public final class StatIndexCache {

    /** Returned (and never cached) when the id is not registered yet / the map is not ready. */
    public static final int UNRESOLVED = -1;

    @Nonnull
    private static final ConcurrentHashMap<String, Integer> CACHE = new ConcurrentHashMap<>();

    /** Channel ids already named as unregistered, by whichever reader met them first. */
    @Nonnull
    private static final Set<String> WARNED_UNKNOWN = ConcurrentHashMap.newKeySet();

    private StatIndexCache() {
    }

    /**
     * The asset-map index of the {@link EntityStatType} registered under {@code statId},
     * memoized once resolved.
     *
     * @return the resolved index ({@code >= 0}), or {@link #UNRESOLVED} if not registered yet /
     *         the asset store is not ready (e.g. a unit JVM with no live server). Never throws.
     */
    public static int resolve(@Nonnull String statId) {
        Integer cached = CACHE.get(statId);
        if (cached != null) {
            return cached;
        }
        int idx;
        try {
            idx = EntityStatType.getAssetMap().getIndex(statId);
        } catch (Throwable ignored) {
            // Asset store not ready (or not present, e.g. a unit JVM) - miss; do not cache.
            return UNRESOLVED;
        }
        if (idx >= 0) {
            CACHE.put(statId, idx);
            return idx;
        }
        return UNRESOLVED;
    }

    /**
     * Names a stat channel nothing is registered under, once per id across every reader that
     * resolves through this cache ({@link EquipStatBridge}, a gear set's tiers, the
     * {@code hytale:stat} and {@code hytale:item_stat} factors), so one misspelled or late channel
     * logs one line rather than one per reader. A miss is never cached by {@link #resolve}, so a
     * channel registered later still resolves; only the log line is latched. Never throws.
     */
    public static void warnUnknownOnce(@Nonnull String statId) {
        if (WARNED_UNKNOWN.add(statId)) {
            SafeLog.warn("[stats] unknown stat channel '" + statId
                    + "' - skipping (not registered yet, or misspelled)");
        }
    }
}
