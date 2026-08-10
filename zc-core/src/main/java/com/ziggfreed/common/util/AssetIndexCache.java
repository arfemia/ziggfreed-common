package com.ziggfreed.common.util;

import java.util.function.ToIntFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves an asset id (or the first of several candidate ids) to its engine asset
 * index and caches the result, with the HARD "cache only a real index" contract.
 *
 * <p>Hytale asset maps return a sentinel when an id is not registered yet: an
 * unloaded/not-ready map throws, a missing id returns {@code Integer.MIN_VALUE},
 * and index {@code 0} is the engine's "none/clear" slot (several maps reserve it).
 * A naive cache that latched any of those values would re-introduce the silence
 * bugs this class exists to prevent (a sound that resolved before its pack loaded
 * would be permanently muted; a music container that resolved to 0 would clear
 * itself forever). So {@link #UNRESOLVED} (-1), {@code MIN_VALUE} and {@code 0}
 * are ALL treated as "not resolved yet" - never cached - and the next call retries.
 * Only a strictly-positive index is cached.
 *
 * <p>Stateless and thread-safe (the cached index is {@code volatile}); resolution
 * itself is engine-thread-agnostic (the asset map lookup is a pure read). The
 * caller is responsible for the world-thread hop before PLAYING/applying whatever
 * the resolved index points at.
 *
 * <p>Typical use: one static instance per logical asset, given a candidate list
 * (the pack asset first, then vanilla fallbacks) and a lookup that maps an id to
 * its map index, e.g. {@code id -> SoundEvent.getAssetMap().getIndex(id)}.
 */
public final class AssetIndexCache<T> {

    /** Sentinel for "not resolved yet". Distinct from the engine's MIN_VALUE / 0. */
    public static final int UNRESOLVED = -1;

    @Nonnull
    private final String[] candidateIds;

    @Nonnull
    private final ToIntFunction<String> indexLookup;

    /** {@link #UNRESOLVED} until a strictly-positive index is found, then that index. */
    private volatile int cachedIndex = UNRESOLVED;

    private AssetIndexCache(@Nonnull String[] candidateIds, @Nonnull ToIntFunction<String> indexLookup) {
        this.candidateIds = candidateIds;
        this.indexLookup = indexLookup;
    }

    /**
     * Build a cache for a single id.
     *
     * @param id          the asset id to resolve
     * @param indexLookup maps an id to its asset-map index (e.g.
     *                    {@code i -> SoundEvent.getAssetMap().getIndex(i)}); may throw
     *                    if the map is not ready (treated as unresolved)
     */
    @Nonnull
    public static <T> AssetIndexCache<T> of(@Nonnull String id, @Nonnull ToIntFunction<String> indexLookup) {
        return new AssetIndexCache<>(new String[] { id }, indexLookup);
    }

    /**
     * Build a cache that resolves to the FIRST candidate id that registers a
     * positive index (the pack asset first, then vanilla fallbacks).
     *
     * @param candidateIds candidate asset ids, tried in order
     * @param indexLookup  maps an id to its asset-map index; may throw if the map
     *                     is not ready (treated as unresolved)
     */
    @Nonnull
    public static <T> AssetIndexCache<T> ofCandidates(@Nonnull String[] candidateIds,
                                                      @Nonnull ToIntFunction<String> indexLookup) {
        return new AssetIndexCache<>(candidateIds.clone(), indexLookup);
    }

    /**
     * Resolve (and cache on first success) the asset index.
     *
     * @return a strictly-positive asset index, or {@link #UNRESOLVED} if no
     *         candidate is registered yet / the map is not ready. NEVER returns
     *         {@code Integer.MIN_VALUE} or {@code 0}; callers should skip the action
     *         and retry next tick on {@link #UNRESOLVED}.
     */
    public int resolve() {
        int cached = cachedIndex;
        if (cached != UNRESOLVED) {
            return cached;
        }
        for (String id : candidateIds) {
            if (id == null || id.isEmpty()) {
                continue;
            }
            int i;
            try {
                i = indexLookup.applyAsInt(id);
            } catch (Throwable t) {
                // Asset map not ready. Do NOT cache; retry next call.
                return UNRESOLVED;
            }
            // Cache ONLY a strictly-positive index. MIN_VALUE (missing) and 0
            // (engine "none/clear" slot) are NOT real assets and must re-resolve.
            if (i != Integer.MIN_VALUE && i > 0) {
                cachedIndex = i;
                return i;
            }
        }
        // Nothing registered yet; do not cache, re-resolve next call.
        return UNRESOLVED;
    }

    /** @return true if a positive index is already cached. */
    public boolean isResolved() {
        return cachedIndex != UNRESOLVED;
    }

    /** Drop the cached index so the next {@link #resolve()} re-runs the lookup. */
    public void invalidate() {
        cachedIndex = UNRESOLVED;
    }

    /** @return the id that resolved, or null if not yet resolved (best-effort). */
    @Nullable
    public String resolvedIdOrNull() {
        if (cachedIndex == UNRESOLVED) {
            return null;
        }
        for (String id : candidateIds) {
            if (id == null || id.isEmpty()) {
                continue;
            }
            try {
                if (indexLookup.applyAsInt(id) == cachedIndex) {
                    return id;
                }
            } catch (Throwable ignored) {
                // fall through to next candidate
            }
        }
        return null;
    }
}
