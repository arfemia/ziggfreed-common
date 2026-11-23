package com.ziggfreed.common.progress.asset;

import java.nio.file.Path;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.asset.OwnerLayerReader;
import com.ziggfreed.common.util.OwnerFiles;

/**
 * The {@code defaults < pack < owner} fold of every {@link GeneratorRowAsset}, keyed by row id. The owner layer,
 * from {@code mods/ziggfreedcommon/}{@value #OWNER_FILE}, is a map from a row id to the leaves that row should read
 * differently, decoded against the packs' own row through the same codec, so one value under {@code Values} is
 * retuned alone and an entry for an id no pack ships adds a row of the owner's own:
 *
 * <pre>{@code
 * { "Yours_School_Fire": { "Values": { "First": 6 } },     // retune one member's value
 *   "Yours_School_Ice":  { "Enabled": false } }             // or take a member out of its set
 * }</pre>
 *
 * <p>A generator reads the fold through {@link GeneratorRows}; after the packs land and after every read of the
 * owner file, {@link #reloadOwner} settles it there, which re-expands every generator store that walked a set
 * whose rows moved.
 */
public final class GeneratorRowConfig extends AbstractKeyedAssetConfig<GeneratorRowAsset> {

    /** The owner file over the rows. */
    public static final String OWNER_FILE = "generator-rows.json";

    private static final GeneratorRowConfig INSTANCE = new GeneratorRowConfig();

    @Nonnull
    private static volatile Path directory = OwnerFiles.DIRECTORY;

    private GeneratorRowConfig() {
    }

    @Nonnull
    public static GeneratorRowConfig getInstance() {
        return INSTANCE;
    }

    /** Point the owner file at another directory (a test, or a consumer with its own data dir). */
    public static void setDirectory(@Nonnull Path dir) {
        directory = dir;
    }

    /**
     * (Re)read the owner file into the owner layer, then settle the rows ({@link GeneratorRows#settle}): every
     * generator store that walked a set whose rows moved is expanded again. Run from the store's own load event,
     * after the packs; whatever writes the file while the server runs (an owner screen's binding) runs it after its
     * write, since nothing watches the file.
     */
    public static void reloadOwner() {
        OwnerLayerReader.apply("generator", directory.resolve(OWNER_FILE), GeneratorRowAsset.class,
                GeneratorRowAsset.CODEC, INSTANCE, "generator row");
        GeneratorRows.settle();
    }
}
