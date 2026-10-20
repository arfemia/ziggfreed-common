package com.ziggfreed.common.damage.weakspot;

import java.nio.file.Path;
import java.util.Map;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.asset.OwnerLayerReader;
import com.ziggfreed.common.util.OwnerFiles;

/**
 * The {@code defaults < pack < owner} fold of {@link WeakSpotSettingsAsset}, keyed by record id. The library ships
 * {@code Default.json} in its own jar's asset pack, so it rides the pack layer; the owner layer, from
 * {@code mods/ziggfreedcommon/}{@value #OWNER_FILE}, wins over both. A server with no record at all reads every
 * default ({@link #settings}). Every merge marks the window table, since it names effects.
 */
public final class WeakSpotSettingsConfig extends AbstractKeyedAssetConfig<WeakSpotSettingsAsset> {

    /** The owner file over the settings. */
    public static final String OWNER_FILE = "weak-spots.json";

    private static final WeakSpotSettingsConfig INSTANCE = new WeakSpotSettingsConfig();

    /** What a server with no record reads: every leaf unauthored. */
    private static final WeakSpotSettingsAsset DEFAULTS = new WeakSpotSettingsAsset();

    @Nonnull
    private static volatile Path directory = OwnerFiles.DIRECTORY;

    private WeakSpotSettingsConfig() {
    }

    @Nonnull
    public static WeakSpotSettingsConfig getInstance() {
        return INSTANCE;
    }

    /** Point the owner file at another directory (a test, or a consumer with its own data dir). */
    public static void setDirectory(@Nonnull Path dir) {
        directory = dir;
    }

    /** (Re)read the owner file into the owner layer. From the store's own load event, after the packs. */
    public static void reloadOwner() {
        OwnerLayerReader.apply("weakspot", directory.resolve(OWNER_FILE), WeakSpotSettingsAsset.class,
                WeakSpotSettingsAsset.CODEC, INSTANCE, "weak spot settings");
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, WeakSpotSettingsAsset> layer) {
        super.mergePackLayer(layer);
        WeakSpotWindows.markDirty();
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, WeakSpotSettingsAsset> layer) {
        super.mergeOwnerLayer(layer);
        WeakSpotWindows.markDirty();
    }

    /** The folded record every hit reads, or the defaults when no layer authored one. */
    @Nonnull
    public WeakSpotSettingsAsset settings() {
        return resolveOrDefault(WeakSpotSettingsAsset.SHARED_ID, DEFAULTS);
    }
}
