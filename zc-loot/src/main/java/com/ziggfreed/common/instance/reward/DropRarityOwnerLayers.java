package com.ziggfreed.common.instance.reward;

import java.nio.file.Path;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.OwnerLayerReader;
import com.ziggfreed.common.util.OwnerFiles;

/**
 * The server owner's word on the drop-rarity bands, in {@code mods/ziggfreedcommon/drop-rarities.json}: each
 * top-level entry is a band's quality id, spelled as the item quality's own id, and the leaves it should read
 * differently, decoded against the packs' own answer through the same codec; a new id is a band of the owner's own.
 *
 * <pre>{@code
 * // mods/ziggfreedcommon/drop-rarities.json
 * { "Legendary": { "AtMostPercent": 0.5 },
 *   "Uncommon":  { "Enabled": false } }
 * }</pre>
 *
 * <p>A {@code $}-prefixed key ({@code $Comment}, {@code $SchemaVersion}) is file-level and never a band. Read from the
 * band store's own load event, since an owner entry has nothing to inherit from until the packs have landed.
 */
public final class DropRarityOwnerLayers {

    /** Where a server owner's files live. */
    public static final Path DEFAULT_DIRECTORY = OwnerFiles.DIRECTORY;

    /** The owner file the bands are read from. */
    public static final String FILE = "drop-rarities.json";

    private static final String LOG_TAG = "loot";

    @Nonnull
    private static volatile Path directory = DEFAULT_DIRECTORY;

    private DropRarityOwnerLayers() {
    }

    /** Point the owner file at a different directory (a test, or a consumer with its own data dir). */
    public static void setDirectory(@Nonnull Path dir) {
        directory = dir;
    }

    /** Where the owner file is read from. */
    @Nonnull
    public static Path directory() {
        return directory;
    }

    /** (Re)read {@code drop-rarities.json} into the band fold's owner layer. */
    public static void reload() {
        OwnerLayerReader.apply(LOG_TAG, directory.resolve(FILE), DropRarityAsset.class, DropRarityAsset.CODEC,
                DropRarityConfig.getInstance(), "drop rarity band");
    }
}
