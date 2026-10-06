package com.ziggfreed.common.settings;

import java.nio.file.Path;
import java.nio.file.Paths;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.OwnerLayerReader;

/**
 * The SERVER OWNER's last word on players' defaults and locks, at
 * {@code mods/ziggfreedcommon/player-settings.json}: a bare map from a record id to the leaves that
 * record should read differently, decoded against the packs' own answer through the same codec, exactly
 * like every other owner file this library reads.
 *
 * <pre>{@code
 * // mods/ziggfreedcommon/player-settings.json
 * { "Default": { "QuestTracker": { "Show": { "Default": false } } } }
 * }</pre>
 *
 * <p>Read from the store's own load event, since an owner entry has nothing to inherit from until the
 * packs have landed.
 */
public final class PlayerSettingsOwnerLayers {

    /** Where a server owner's files live. */
    public static final Path DEFAULT_DIRECTORY = Paths.get("mods", "ziggfreedcommon");

    /** The owner file over the record. */
    public static final String FILE = "player-settings.json";

    private static final String LOG_TAG = "settings";

    @Nonnull
    private static volatile Path directory = DEFAULT_DIRECTORY;

    private PlayerSettingsOwnerLayers() {
    }

    /** Point the owner file at a different directory (a test, or a consumer with its own data dir). */
    public static void setDirectory(@Nonnull Path dir) {
        directory = dir;
    }

    /** Where the owner file is being read from. */
    @Nonnull
    public static Path directory() {
        return directory;
    }

    /** (Re)read {@code player-settings.json} into the record fold's owner layer. */
    public static void reload() {
        OwnerLayerReader.apply(LOG_TAG, directory.resolve(FILE), PlayerSettingsAsset.class,
                PlayerSettingsAsset.CODEC, PlayerSettingsConfig.getInstance(), "player-settings record");
    }
}
