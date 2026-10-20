package com.ziggfreed.common.stats.condition;

import java.nio.file.Path;
import java.util.Map;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.asset.OwnerLayerReader;
import com.ziggfreed.common.util.OwnerFiles;

/**
 * The {@code defaults < pack < owner} fold of {@link EffectFamilyAsset}, keyed by family id. The library ships its
 * families in its own jar's asset pack, so they ride the pack layer; the owner layer, from
 * {@code mods/ziggfreedcommon/}{@value #OWNER_FILE}, wins over both. Every merge marks the family table.
 */
public final class EffectFamilyConfig extends AbstractKeyedAssetConfig<EffectFamilyAsset> {

    /** The owner file over the families. */
    public static final String OWNER_FILE = "effect-families.json";

    private static final EffectFamilyConfig INSTANCE = new EffectFamilyConfig();

    @Nonnull
    private static volatile Path directory = OwnerFiles.DIRECTORY;

    private EffectFamilyConfig() {
    }

    @Nonnull
    public static EffectFamilyConfig getInstance() {
        return INSTANCE;
    }

    /** Point the owner file at another directory (a test, or a consumer with its own data dir). */
    public static void setDirectory(@Nonnull Path dir) {
        directory = dir;
    }

    /** (Re)read the owner file into the owner layer. From the store's own load event, after the packs. */
    public static void reloadOwner() {
        OwnerLayerReader.apply("effectfamily", directory.resolve(OWNER_FILE), EffectFamilyAsset.class,
                EffectFamilyAsset.CODEC, INSTANCE, "effect family");
    }

    @Override
    public synchronized void loadDefaults(@Nonnull Map<String, EffectFamilyAsset> jarDefaults) {
        super.loadDefaults(jarDefaults);
        EffectFamilies.markDirty();
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, EffectFamilyAsset> layer) {
        super.mergePackLayer(layer);
        EffectFamilies.markDirty();
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, EffectFamilyAsset> layer) {
        super.mergeOwnerLayer(layer);
        EffectFamilies.markDirty();
    }
}
