package com.ziggfreed.common.instance.dungeon;

import java.nio.file.Path;
import java.nio.file.Paths;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.OwnerLayerReader;

/**
 * The server owner's word on the dungeons, in {@code mods/ziggfreedcommon/dungeons.json}: each top-level
 * entry is a dungeon id and the leaves it should read differently, decoded against the packs' own answer
 * through the same codec, and a new id is a dungeon of the owner's own.
 *
 * <pre>{@code
 * // mods/ziggfreedcommon/dungeons.json
 * { "Crypt": { "Share": { "AreasPerCopy": 2 }, "Copy": { "EmptyCloseSeconds": 30 } },
 *   "Old_Mine": { "Enabled": false } }
 * }</pre>
 *
 * <p>A {@code $}-prefixed key ({@code $Comment}, {@code $SchemaVersion}) is file-level and never a dungeon.
 * Read from the dungeon store's own load event, since an owner entry has nothing to inherit from until the
 * packs have landed.
 */
public final class DungeonOwnerLayers {

    /** Where a server owner's files live. */
    public static final Path DEFAULT_DIRECTORY = Paths.get("mods", "ziggfreedcommon");

    /** The owner file the dungeons are read from. */
    public static final String FILE = "dungeons.json";

    private static final String LOG_TAG = "dungeon";

    @Nonnull
    private static volatile Path directory = DEFAULT_DIRECTORY;

    private DungeonOwnerLayers() {
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

    /** (Re)read {@code dungeons.json} into the dungeon fold's owner layer. */
    public static void reload() {
        OwnerLayerReader.apply(LOG_TAG, directory.resolve(FILE), DungeonAsset.class, DungeonAsset.CODEC,
                DungeonConfig.getInstance(), "dungeon");
    }
}
