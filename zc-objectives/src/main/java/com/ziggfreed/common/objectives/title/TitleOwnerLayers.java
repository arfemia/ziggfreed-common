package com.ziggfreed.common.objectives.title;

import java.nio.file.Path;
import java.nio.file.Paths;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.OwnerLayerReader;

/**
 * The SERVER OWNER's last word on titles, at {@code mods/ziggfreedcommon/titles.json}: a map from a
 * title id to the leaves it should read differently, decoded against the packs' own answer through
 * the same codec, like every owner file this library reads.
 *
 * <pre>{@code
 * { "Hallows_Eve_Hallowed": { "Enabled": false } }
 * }</pre>
 *
 * <p>Read from the store's own load event, since an owner entry has nothing to inherit from until
 * the packs have landed.
 */
public final class TitleOwnerLayers {

    /** Where a server owner's files live. */
    public static final Path DEFAULT_DIRECTORY = Paths.get("mods", "ziggfreedcommon");

    /** The owner file over the titles. */
    public static final String TITLES_FILE = "titles.json";

    private static final String LOG_TAG = "title";

    @Nonnull
    private static volatile Path directory = DEFAULT_DIRECTORY;

    private TitleOwnerLayers() {
    }

    /** Point the owner file at another directory (a test, or a consumer with its own data dir). */
    public static void setDirectory(@Nonnull Path dir) {
        directory = dir;
    }

    /** Where the owner file is read from. */
    @Nonnull
    public static Path directory() {
        return directory;
    }

    /** (Re)read {@code titles.json} into the fold's owner layer. */
    public static void reload() {
        OwnerLayerReader.apply(LOG_TAG, directory.resolve(TITLES_FILE), TitleAsset.class,
                TitleAsset.CODEC, TitleConfig.getInstance(), "title");
    }
}
