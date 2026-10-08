package com.ziggfreed.common.asset;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.annotation.Nonnull;

import com.hypixel.hytale.assetstore.JsonAsset;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.ziggfreed.common.factor.ModGates;

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
 *
 * <p>A store whose files can be gated on a mod folds through {@link #gate}, which drops them, names the
 * mod that dropped each, and logs the drop; {@link #layer(DefaultAssetMap, Predicate)} and
 * {@link #refused} are the same filter without the report.
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

    /**
     * Rebuild the pack layer, leaving out every asset {@code keep} refuses: the load handler's form for
     * a store whose files can be gated on a mod being installed (pass the store's {@code passesModGate}
     * read), so a refused file never reaches the store, its folds, its validators or its audits.
     */
    @Nonnull
    public static <T extends JsonAsset<String>> Map<String, T> layer(@Nonnull DefaultAssetMap<String, T> assetMap,
                                                                     @Nonnull Predicate<T> keep) {
        return layer(assetMap, keep, (id, asset) -> asset);
    }

    /** {@link #layer(DefaultAssetMap, BiFunction)} with the {@code keep} filter applied first. */
    @Nonnull
    public static <T extends JsonAsset<String>, V> Map<String, V> layer(@Nonnull DefaultAssetMap<String, T> assetMap,
                                              @Nonnull Predicate<T> keep, @Nonnull BiFunction<String, T, V> mapper) {
        return layer(assetMap, (id, asset) -> keep.test(asset) ? mapper.apply(id, asset) : null);
    }

    /**
     * The ids {@code keep} refuses in {@code assetMap}, lower-cased, engine-base entries skipped: what a
     * store that resolves one file against another (a generator's {@code Base}) needs, to leave out a
     * family whose base was refused instead of reporting the base as missing. An asset's own id is used
     * where it has one (a marked folder folds into it), else the map key.
     */
    @Nonnull
    public static <T extends JsonAsset<String>> Set<String> refused(@Nonnull DefaultAssetMap<String, T> assetMap,
                                                                    @Nonnull Predicate<T> keep) {
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, T> entry : assetMap.getAssetMap().entrySet()) {
            String key = entry.getKey();
            T asset = entry.getValue();
            if (asset == null || DefaultAssetMap.DEFAULT_PACK_KEY.equals(assetMap.getAssetPack(key))
                    || keep.test(asset)) {
                continue;
            }
            out.add(idOf(key, asset));
        }
        return out;
    }

    /**
     * The load handler's fold for a store whose files can be gated on a mod being installed: the layer
     * {@link #layer(DefaultAssetMap, Predicate)} would build, every refused id with the mod that refused
     * it ({@link ModGateFold#refused()}, in the id space {@link #refused} uses), and one counted line per
     * missing mod through {@link ModGates#reportPackFiles}, naming no file.
     *
     * @param store      the registrar's name for the store, which the drop line carries
     * @param missingMod the store's read of a file's top-level {@code Requires} (its {@code missingMod}
     *                   helper): the mod that keeps the file out, or null when it loads here
     */
    @Nonnull
    public static <T extends JsonAsset<String>> ModGateFold<T> gate(@Nonnull String store,
            @Nonnull DefaultAssetMap<String, T> assetMap, @Nonnull Function<T, String> missingMod) {
        Map<String, T> layer = new LinkedHashMap<>();
        Map<String, String> refused = new LinkedHashMap<>();
        List<String> dropped = new ArrayList<>();
        for (Map.Entry<String, T> entry : assetMap.getAssetMap().entrySet()) {
            String key = entry.getKey();
            T asset = entry.getValue();
            if (asset == null || DefaultAssetMap.DEFAULT_PACK_KEY.equals(assetMap.getAssetPack(key))) {
                continue;
            }
            String mod = missingMod.apply(asset);
            if (mod == null) {
                layer.put(key.toLowerCase(Locale.ROOT), asset);
                continue;
            }
            refused.put(idOf(key, asset), mod);
            dropped.add(mod);
        }
        ModGates.reportPackFiles(store, dropped);
        return new ModGateFold<>(store, layer, refused);
    }

    /** An asset's own id where it has one (a marked folder folds into it), else its map key, lower-cased. */
    @Nonnull
    private static String idOf(@Nonnull String key, @Nonnull JsonAsset<String> asset) {
        String id = asset.getId();
        return (id == null || id.isBlank() ? key : id).toLowerCase(Locale.ROOT);
    }
}
