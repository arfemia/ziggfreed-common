package com.ziggfreed.common.objectives.indicator;

import java.nio.file.Path;
import java.nio.file.Paths;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.OwnerLayerReader;

/**
 * The SERVER OWNER's last word on quest indicators, at
 * {@code mods/ziggfreedcommon/quest-indicators.json}: a bare map from an id to the leaves that id
 * should read differently, decoded against the packs' own answer through the same codec the file
 * uses, exactly like the economy's, the placement engine's and the encounter owner files.
 *
 * <pre>{@code
 * // mods/ziggfreedcommon/quest-indicators.json
 * {
 *   "Default": { "InProgress": { "Overhead": { "Enabled": false } } }
 * }
 * }</pre>
 *
 * <p>Read from the store's own load event (an owner entry has nothing to inherit from until the
 * packs have landed).
 */
public final class QuestIndicatorOwnerLayers {

    /** Where a server owner's file lives. */
    public static final Path DEFAULT_DIRECTORY = Paths.get("mods", "ziggfreedcommon");

    /** The owner file over the global word. */
    public static final String FILE = "quest-indicators.json";

    private static final String LOG_TAG = "progression";

    @Nonnull
    private static volatile Path directory = DEFAULT_DIRECTORY;

    private QuestIndicatorOwnerLayers() {
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

    /** (Re)read {@code quest-indicators.json} into the fold's owner layer. */
    public static void reload() {
        OwnerLayerReader.apply(LOG_TAG, directory.resolve(FILE), QuestIndicatorAsset.class,
                QuestIndicatorAsset.CODEC, QuestIndicatorConfig.getInstance(), "quest indicator");
    }
}
