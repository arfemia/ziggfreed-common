package com.ziggfreed.common.asset;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;

/**
 * What a gated store's pack fold produced ({@link AssetMergeAdapter#gate}): the layer that loads here,
 * and every id the mod gate refused, mapped to the missing mod that refused it.
 *
 * <p>A store that keeps an owner layer hands the whole fold to
 * {@link AbstractKeyedAssetConfig#mergePackLayer(ModGateFold)}, which remembers the refusals so an owner
 * override of a refused file goes with it; a store resolving a generator's {@code Base} reads
 * {@link #refusedIds()} so a family over a refused base stays silent.
 *
 * @param store   the registrar's name for the store, which the drop lines carry
 * @param layer   the loaded files, keyed by lower-cased id, exactly as {@code AssetMergeAdapter.layer}
 *                keys them
 * @param refused each refused file's id (its own id where it has one, lower-cased) to its missing mod
 */
public record ModGateFold<T>(@Nonnull String store, @Nonnull Map<String, T> layer,
        @Nonnull Map<String, String> refused) {

    public ModGateFold {
        layer = Collections.unmodifiableMap(new LinkedHashMap<>(layer));
        refused = Collections.unmodifiableMap(new LinkedHashMap<>(refused));
    }

    /** The ids the mod gate refused, lower-cased. */
    @Nonnull
    public Set<String> refusedIds() {
        return refused.keySet();
    }
}
