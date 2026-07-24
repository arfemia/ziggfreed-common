package com.ziggfreed.common.stats;

import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;

/**
 * Package-private: resolves a native {@link EntityStatType} id to its engine asset-map index and
 * memoizes the result, mirroring {@code util.DamageCauseCache}'s technique for a different asset
 * type (same repo, different domain). Shared by every class in this package that needs to turn a
 * stat id string into an index ({@link EquipStatBridge}, {@link StatMirror}, {@link
 * StatChannelAudit}) so the resolution + caching rule exists exactly once.
 *
 * <p><b>Deliberately NOT routed through {@code util.AssetIndexCache}</b> even though the package
 * router lists it as the one allowed cross-domain primitive: that cache treats index {@code 0} as
 * "not resolved yet" (several engine asset maps reserve slot 0 as a none/clear sentinel), but a
 * custom {@link EntityStatType} can legitimately land at index 0 depending on pack load order -
 * caching only {@code idx > 0} would permanently mis-resolve such a channel as unregistered. The
 * correct rule here is the same one {@code util.DamageCauseCache} uses for its own asset type:
 * cache any {@code idx >= 0} (the engine's real "missing" sentinel is {@code Integer.MIN_VALUE}),
 * never cache a miss so a not-yet-loaded pack channel re-resolves on the next call.
 */
final class StatIndexCache {

    /** Returned (and never cached) when the id is not registered yet / the map is not ready. */
    static final int UNRESOLVED = -1;

    @Nonnull
    private static final ConcurrentHashMap<String, Integer> CACHE = new ConcurrentHashMap<>();

    private StatIndexCache() {
    }

    /**
     * The asset-map index of the {@link EntityStatType} registered under {@code statId},
     * memoized once resolved.
     *
     * @return the resolved index ({@code >= 0}), or {@link #UNRESOLVED} if not registered yet /
     *         the asset store is not ready (e.g. a unit JVM with no live server). Never throws.
     */
    static int resolve(@Nonnull String statId) {
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

    /** Test hook: drop every cached index so the next {@link #resolve} re-runs the lookup. */
    static void invalidateAll() {
        CACHE.clear();
    }
}
