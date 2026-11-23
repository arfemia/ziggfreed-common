package com.ziggfreed.common.track.source;

import java.nio.file.Path;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.asset.OwnerLayerReader;
import com.ziggfreed.common.util.OwnerFiles;

/**
 * The {@code defaults < pack < owner} fold of every {@link DiminishKeyAsset}, keyed by id, and its owner file
 * {@code mods/ziggfreedcommon/diminish-keys.json} (a map from a key's id to the leaves it should read differently,
 * decoded against the packs' answer through the same codec; the owner menu's Diminishing Keys screen writes it). A
 * source's {@code Diminish.Key} is read through {@link #key} at each moment, so a fold takes effect on the next one.
 */
public final class DiminishKeyConfig extends AbstractKeyedAssetConfig<DiminishKeyAsset> {

    /** The owner file. */
    public static final String FILE = "diminish-keys.json";

    /** The file's id as an owner form names it: its path under {@code mods/}. */
    public static final String BINDING = "ziggfreedcommon/" + FILE;

    private static final DiminishKeyConfig INSTANCE = new DiminishKeyConfig();

    @Nonnull
    private static volatile Path directory = OwnerFiles.DIRECTORY;

    private DiminishKeyConfig() {
    }

    @Nonnull
    public static DiminishKeyConfig getInstance() {
        return INSTANCE;
    }

    /** The key {@code keyId} names (any case, trimmed), or null for a blank id or one no layer holds. */
    @Nullable
    public DiminishKeyAsset key(@Nullable String keyId) {
        return keyId == null || keyId.isBlank() ? null : resolve(keyId.trim());
    }

    /** Point the owner file at another directory (a test, or a consumer with its own data dir). */
    public static void setDirectory(@Nonnull Path dir) {
        directory = dir;
    }

    /** Where the owner's file lives right now. */
    @Nonnull
    public static Path file() {
        return directory.resolve(FILE);
    }

    /** (Re)read {@code diminish-keys.json} into the fold's owner layer. */
    public static void reloadOwnerLayer() {
        OwnerLayerReader.apply("track", file(), DiminishKeyAsset.class, DiminishKeyAsset.CODEC, INSTANCE,
                "diminishing key");
    }
}
