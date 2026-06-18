package com.ziggfreed.common.asset;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;

import javax.annotation.Nonnull;

import com.hypixel.hytale.assetstore.JsonAsset;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;

/**
 * The generic pack-layer fold for a {@code LoadedAssetsEvent} handler: rebuild the
 * full {@code id -> asset} layer from the loaded map, skipping the engine-base
 * ({@link DefaultAssetMap#DEFAULT_PACK_KEY}) entries and lower-casing each id so author
 * casing never splits an entry. Idempotent on hot re-import (the whole layer is rebuilt
 * from the event, never accumulated), exactly like Kweebec's / hyMMO's per-type fold.
 *
 * <p>A consumer's listener does:
 * <pre>{@code
 * plugin.getEventRegistry().register(LoadedAssetsEvent.class, MyAsset.class, ev ->
 *     MyConfig.getInstance().mergePackLayer(
 *         AssetMergeAdapter.layer(ev.getAssetMap(), (id, a) -> a.toModel(id)), replace));
 * }</pre>
 */
public final class AssetMergeAdapter {

    private AssetMergeAdapter() {
    }

    /** Rebuild the {@code id -> asset} pack layer (lower-cased ids, engine-base skipped). */
    @Nonnull
    public static <T extends JsonAsset<String>> Map<String, T> layer(@Nonnull DefaultAssetMap<String, T> assetMap) {
        return layer(assetMap, (id, asset) -> asset);
    }

    /**
     * Rebuild the pack layer, mapping each asset to a value (e.g. {@code toModel(id)}).
     * Entries the {@code mapper} maps to {@code null} are dropped.
     */
    @Nonnull
    public static <T extends JsonAsset<String>, V> Map<String, V> layer(@Nonnull DefaultAssetMap<String, T> assetMap,
                                              @Nonnull BiFunction<String, T, V> mapper) {
        Map<String, V> out = new LinkedHashMap<>();
        for (Map.Entry<String, T> entry : assetMap.getAssetMap().entrySet()) {
            String key = entry.getKey();
            if (DefaultAssetMap.DEFAULT_PACK_KEY.equals(assetMap.getAssetPack(key))) {
                continue;
            }
            T asset = entry.getValue();
            if (asset == null) {
                continue;
            }
            String id = key.toLowerCase(Locale.ROOT);
            V value = mapper.apply(id, asset);
            if (value != null) {
                out.put(id, value);
            }
        }
        return out;
    }
}
