package com.ziggfreed.common.asset;

import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetMap;
import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.codec.AssetCodec;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.server.core.asset.HytaleAssetStore;

/**
 * The generic, mod-agnostic lift of hyMMO's / Kweebec's {@code registerStore} helper:
 * registers ONE Pattern-A custom asset store so a pack can author
 * {@code Server/<consumer-path>/*.json} decoded directly by a structured
 * {@link AssetCodec}. A consumer calls this from its OWN plugin {@code setup()} and
 * supplies the asset class, its empty {@link AssetMap}, the content path, the id
 * function, the CODEC, and any {@code loadsAfter} dependencies - common hard-codes no
 * mod name and registers nothing on its own (the plugin stays dependency-free).
 *
 * <p>The single tricky part this hides is the package-protected base
 * {@code AssetStore.Builder}: we chain through the public
 * {@link HytaleAssetStore.Builder}, casting each fluent result back to it (the
 * inherited setters return the erased base type), exactly as the engine's own built-in
 * plugins and Kweebec's {@code KweebecAssetRegistrar} do.
 *
 * <p>After registering the store, the consumer wires its own
 * {@code LoadedAssetsEvent} listener and folds the loaded entries with
 * {@link AssetMergeAdapter}. Keeping the listener registration consumer-side avoids
 * pinning common to the engine's {@code EventRegistry} functional-interface shape.
 */
public final class AssetStoreRegistrar {

    private AssetStoreRegistrar() {
    }

    /**
     * Register a Pattern-A asset store.
     *
     * @param assetClass the typed asset class (its own {@code static CODEC})
     * @param map        a fresh empty asset map (e.g. {@code new DefaultAssetMap<>()})
     * @param path       the content path under a pack's {@code Server/} (e.g.
     *                   {@code "MyMod/Instances"})
     * @param keyFn      the asset's id getter (e.g. {@code MyAsset::getId})
     * @param codec      the asset's structured {@code AssetBuilderCodec}
     * @param loadsAfter classes whose stores must load first (e.g. a pack-control
     *                   asset), or {@code null} for none
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static <T extends JsonAssetWithMap<String, M>, M extends AssetMap<String, T>> void registerStore(
            @Nonnull Class<T> assetClass, @Nonnull M map, @Nonnull String path,
            @Nonnull Function<T, String> keyFn, @Nonnull AssetCodec<String, T> codec,
            @Nullable Class<?>[] loadsAfter) {
        HytaleAssetStore.Builder b = HytaleAssetStore.builder(assetClass, map);
        b = (HytaleAssetStore.Builder) b.setPath(path);
        b = (HytaleAssetStore.Builder) b.setKeyFunction(keyFn);
        b = (HytaleAssetStore.Builder) b.setCodec(codec);
        if (loadsAfter != null && loadsAfter.length > 0) {
            b = (HytaleAssetStore.Builder) b.loadsAfter(loadsAfter);
        }
        AssetRegistry.register(b.build());
    }
}
