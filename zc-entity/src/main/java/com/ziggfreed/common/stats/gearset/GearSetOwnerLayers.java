package com.ziggfreed.common.stats.gearset;

import java.nio.file.Path;
import java.nio.file.Paths;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.OwnerLayerReader;
import com.ziggfreed.common.asset.PresenceRequiresCodec;

/**
 * The SERVER OWNER's last word on gear sets, at {@code mods/ziggfreedcommon/gear-sets.json}: a bare
 * map from a set id to the leaves that set should read differently, decoded against the packs' own
 * answer through the same codec the pack file uses, exactly like the economy's, the placement
 * engine's and the encounter owner files.
 *
 * <pre>{@code
 * // mods/ziggfreedcommon/gear-sets.json
 * {
 *   "Ranger_Kit": { "Enabled": false }
 * }
 * }</pre>
 *
 * <p>Read from the store's own load event (an owner entry has nothing to inherit from until the
 * packs have landed). Remember that {@code Bonuses} replaces wholesale: an owner retuning one tier
 * restates the ladder. An entry follows the mod gate as a set file does: one gated on a missing mod,
 * or retuning a set the gate kept out, is dropped.
 */
public final class GearSetOwnerLayers {

    /** Where a server owner's file lives. */
    public static final Path DEFAULT_DIRECTORY = Paths.get("mods", "ziggfreedcommon");

    /** The owner file over the folded sets. */
    public static final String FILE = "gear-sets.json";

    private static final String LOG_TAG = "gearset";

    @Nonnull
    private static volatile Path directory = DEFAULT_DIRECTORY;

    private GearSetOwnerLayers() {
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

    /** (Re)read {@code gear-sets.json} into the fold's owner layer. */
    public static void reload() {
        OwnerLayerReader.apply(LOG_TAG, directory.resolve(FILE), GearSetAsset.class,
                GearSetAsset.CODEC, GearSetConfig.getInstance(), "gear set",
                s -> PresenceRequiresCodec.missingMod(s.getRequires()));
    }
}
