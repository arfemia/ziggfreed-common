package com.ziggfreed.common.util;

import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;

/**
 * Resolves a {@link DamageCause} id to its engine asset-map index and memoizes the
 * result, so a damage observer can identify a custom (or vanilla) damage cause by a
 * cheap integer compare against {@code damage.getDamageCauseIndex()} instead of a map
 * lookup per hit.
 *
 * <p>Generalizes the lazy-cache technique from Kweebec's {@code KweebecDamageSystem}
 * (its {@code isMoonbloomCause}) into a shared, mod-agnostic primitive: the consumer
 * supplies the cause id. ONLY a resolved index ({@code >= 0}) is cached - the asset
 * registers AFTER this class loads, so an early lookup that returned the engine's
 * {@code Integer.MIN_VALUE} "missing" sentinel must NOT latch (it would permanently
 * mis-identify the cause); an unresolved id simply re-resolves on the next call.
 *
 * <p>Stateless across causes and thread-safe (a {@link ConcurrentHashMap}); the asset-map
 * lookup is a pure read with no thread affinity, so this can be called from any thread.
 * Every lookup is try-guarded: a not-ready map degrades to {@code -1} (miss), never a throw.
 */
public final class DamageCauseCache {

    /** Returned (and never cached) when the id is not registered yet / the map is not ready. */
    public static final int UNRESOLVED = -1;

    /** Cause id -> resolved asset index; ONLY positive-or-zero (>= 0) entries are ever inserted. */
    @Nonnull
    private static final ConcurrentHashMap<String, Integer> CACHE = new ConcurrentHashMap<>();

    private DamageCauseCache() {
    }

    /**
     * The asset-map index of the damage cause registered under {@code causeId}, memoized once
     * resolved.
     *
     * @param causeId the {@link DamageCause} id (e.g. a pack-authored custom cause)
     * @return the resolved index ({@code >= 0}), or {@link #UNRESOLVED} (-1) if not registered yet
     *         / the map is not ready. NEVER returns the engine's {@code Integer.MIN_VALUE} sentinel;
     *         the caller should treat {@code UNRESOLVED} as "no match" and retry on a later call.
     */
    public static int getCauseIndex(@Nonnull String causeId) {
        Integer cached = CACHE.get(causeId);
        if (cached != null) {
            return cached;
        }
        int idx;
        try {
            idx = DamageCause.getAssetMap().getIndex(causeId);
        } catch (Throwable ignored) {
            // asset map not ready yet -> miss; do NOT cache, retry next call
            return UNRESOLVED;
        }
        // getIndex returns Integer.MIN_VALUE when the id is not registered. Cache ONLY a real
        // (>= 0) index so a pre-registration lookup does not latch the missing sentinel.
        if (idx >= 0) {
            CACHE.put(causeId, idx);
            return idx;
        }
        return UNRESOLVED;
    }

    /** Drop the cached index for {@code causeId} so the next {@link #getCauseIndex(String)} re-resolves it. */
    public static void invalidate(@Nonnull String causeId) {
        CACHE.remove(causeId);
    }

    /** Drop every cached index so the next lookups all re-resolve. */
    public static void invalidateAll() {
        CACHE.clear();
    }
}
