package com.ziggfreed.common.stats.condition;

import java.nio.file.Path;
import java.util.Map;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.asset.OwnerLayerReader;
import com.ziggfreed.common.util.OwnerFiles;

/**
 * The {@code defaults < pack < owner} fold of {@link ConditionalStatAsset}, keyed by file id. The library's own files
 * ride the pack layer with every consumer's; the owner layer, from {@code mods/ziggfreedcommon/}{@value #OWNER_FILE},
 * wins over both (a radius retuned, a stat switched off). Every merge rebuilds the runtime's view
 * ({@link ConditionalStats#reload}).
 */
public final class ConditionalStatConfig extends AbstractKeyedAssetConfig<ConditionalStatAsset> {

    /** The owner file over the conditional stats. */
    public static final String OWNER_FILE = "conditional-stats.json";

    private static final ConditionalStatConfig INSTANCE = new ConditionalStatConfig();

    @Nonnull
    private static volatile Path directory = OwnerFiles.DIRECTORY;

    private ConditionalStatConfig() {
    }

    @Nonnull
    public static ConditionalStatConfig getInstance() {
        return INSTANCE;
    }

    /** Point the owner file at another directory (a test, or a consumer with its own data dir). */
    public static void setDirectory(@Nonnull Path dir) {
        directory = dir;
    }

    /** (Re)read the owner file into the owner layer. From the store's own load event, after the packs. */
    public static void reloadOwner() {
        OwnerLayerReader.apply("condition", directory.resolve(OWNER_FILE), ConditionalStatAsset.class,
                ConditionalStatAsset.CODEC, INSTANCE, "conditional stat");
    }

    @Override
    public synchronized void loadDefaults(@Nonnull Map<String, ConditionalStatAsset> jarDefaults) {
        super.loadDefaults(jarDefaults);
        ConditionalStats.reload();
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, ConditionalStatAsset> layer) {
        super.mergePackLayer(layer);
        ConditionalStats.reload();
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, ConditionalStatAsset> layer) {
        super.mergeOwnerLayer(layer);
        ConditionalStats.reload();
    }
}
