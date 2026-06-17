package com.ziggfreed.common.world;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.asset.type.buildertool.config.BlockTypeListAsset;
import com.ziggfreed.common.ZiggfreedCommonPlugin;

/**
 * Resolves the engine's authored {@code BlockTypeList} assets (the named block groupings the game ships
 * under {@code Server/BlockTypeList/*.json} - e.g. {@code TreeWoodAndLeaves}, {@code AllScatter},
 * {@code PlantsAndTrees}, {@code Soils}, {@code Rock}) into a flat {@link Set} of block-type KEYS a
 * consumer can test membership against (e.g. {@link SurfaceProbe}'s foliage-skip set).
 *
 * <p>Asset-driven on purpose: the lists are vanilla data, so as the game adds/renames tree or scatter
 * blocks the grouping follows automatically and no consumer hardcodes a block-name list. The union is
 * cached per (sorted) id-set, so repeated lookups (e.g. once per runtime paste) are cheap. Every read is
 * try-guarded: a missing/unloaded list contributes nothing rather than throwing into the caller.
 *
 * <p><b>Call after asset registration</b> (i.e. at runtime, not during plugin class-init); the asset
 * store is populated when the server has loaded its packs.
 */
public final class BlockTypeLists {

    /** Cache keyed by the sorted, joined list ids -> the unioned, unmodifiable key set. */
    private static final Map<String, Set<String>> CACHE = new ConcurrentHashMap<>();

    private BlockTypeLists() {
    }

    /**
     * The union of the block-type keys of every named {@code BlockTypeList} in {@code listIds}.
     * Cached; the returned set is unmodifiable and never {@code null} (empty if none resolve).
     *
     * @param listIds the {@code BlockTypeList} asset ids (e.g. {@code "TreeWoodAndLeaves"}, {@code "AllScatter"})
     */
    @Nonnull
    public static Set<String> keys(@Nonnull String... listIds) {
        if (listIds.length == 0) {
            return Collections.emptySet();
        }
        String[] sorted = listIds.clone();
        java.util.Arrays.sort(sorted);
        String cacheKey = String.join(",", sorted);
        return CACHE.computeIfAbsent(cacheKey, k -> resolve(sorted));
    }

    @Nonnull
    private static Set<String> resolve(@Nonnull String[] listIds) {
        Set<String> union = new HashSet<>();
        for (String id : listIds) {
            try {
                BlockTypeListAsset list = BlockTypeListAsset.getAssetMap().getAsset(id);
                if (list != null) {
                    union.addAll(list.getBlockTypeKeys());
                } else {
                    ZiggfreedCommonPlugin.LOGGER.atWarning().log(
                            "[ZiggfreedCommon] BlockTypeList '" + id + "' not found (skipped)");
                }
            } catch (Throwable t) {
                ZiggfreedCommonPlugin.LOGGER.atWarning().log(
                        "[ZiggfreedCommon] BlockTypeList '" + id + "' resolve failed: " + t.getMessage());
            }
        }
        return Collections.unmodifiableSet(union);
    }
}
