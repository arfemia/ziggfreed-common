package com.ziggfreed.common.world.gather;

import java.nio.file.Path;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.OwnerLayerReader;
import com.ziggfreed.common.util.OwnerFiles;

/**
 * The server owner's last word on the block families, at {@code mods/ziggfreedcommon/block-families.json}: a bare
 * map from a family id to the leaves that family should read differently, decoded against the packs' own answer
 * through the family's own codec.
 *
 * <pre>{@code
 * { "Hard_Stone": { "Match": { "Blocks": ["Rock_Basalt*", "Rock_Volcanic*"] } },
 *   "Thrift": { "Enabled": false } }
 * }</pre>
 *
 * <p>An id no pack authored stands on its own as a new family. Read from the store's own load event, since an owner
 * entry has nothing to inherit from until the packs have landed. Families carry no {@code Requires}, so the mod gate
 * never drops an entry.
 */
public final class BlockFamilyOwnerLayers {

    /** The owner file over the families. */
    public static final String FILE = "block-families.json";

    private static final String LOG_TAG = "gather";

    @Nonnull
    private static volatile Path directory = OwnerFiles.DIRECTORY;

    private BlockFamilyOwnerLayers() {
    }

    /** Point the owner file at a different directory (a test, or a consumer with its own data dir). */
    public static void setDirectory(@Nonnull Path dir) {
        directory = dir;
    }

    /** The owner file being read. */
    @Nonnull
    public static Path file() {
        return directory.resolve(FILE);
    }

    /** (Re)read the owner file into the family fold's owner layer. */
    public static void reload() {
        OwnerLayerReader.apply(LOG_TAG, file(), BlockFamilyAsset.class, BlockFamilyAsset.CODEC,
                BlockFamilyConfig.getInstance(), "block family");
    }
}
