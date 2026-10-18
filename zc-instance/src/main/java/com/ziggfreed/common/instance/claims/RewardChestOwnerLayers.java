package com.ziggfreed.common.instance.claims;

import java.nio.file.Path;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.OwnerLayerReader;
import com.ziggfreed.common.util.OwnerFiles;

/**
 * The server owner's word on the reward chests, in {@code mods/ziggfreedcommon/reward-chests.json}: each top-level
 * entry is a chest id and the leaves it should read differently, decoded against the packs' own answer through the
 * same codec, and a new id is a chest of the owner's own.
 *
 * <pre>{@code
 * // mods/ziggfreedcommon/reward-chests.json
 * { "Zc_Spoils_Chest": { "Despawn": { "Lifetime": 600 } },
 *   "Zc_Reward_Chest": { "Look": { "Prop": "Furniture_Ancient_Chest_Small" } } }
 * }</pre>
 *
 * <p>A {@code $}-prefixed key ({@code $Comment}, {@code $SchemaVersion}) is file-level and never a chest. Read from the
 * chest store's own load event, since an owner entry has nothing to inherit from until the packs have landed.
 */
public final class RewardChestOwnerLayers {

    /** Where a server owner's files live. */
    public static final Path DEFAULT_DIRECTORY = OwnerFiles.DIRECTORY;

    /** The owner file the reward chests are read from. */
    public static final String FILE = "reward-chests.json";

    private static final String LOG_TAG = "spoils";

    @Nonnull
    private static volatile Path directory = DEFAULT_DIRECTORY;

    private RewardChestOwnerLayers() {
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

    /** (Re)read {@code reward-chests.json} into the reward chest fold's owner layer. */
    public static void reload() {
        OwnerLayerReader.apply(LOG_TAG, directory.resolve(FILE), RewardChestAsset.class, RewardChestAsset.CODEC,
                RewardChestConfig.getInstance(), "reward chest");
    }
}
