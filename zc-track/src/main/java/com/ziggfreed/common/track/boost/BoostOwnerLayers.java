package com.ziggfreed.common.track.boost;

import java.nio.file.Path;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.OwnerLayerReader;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.util.OwnerFiles;

/**
 * The server owner's word on boosts, at {@code mods/ziggfreedcommon/boosts.json}: a map from a boost id to the
 * leaves that boost should read differently, decoded against the packs' own answer through the same codec, and
 * the server-wide settings beside it in {@code boost-settings.json}.
 *
 * <pre>{@code
 * // mods/ziggfreedcommon/boosts.json
 * { "Yourpack_Harvest_Boost": { "Percent": 75 },     // retune one, leaf by leaf
 *   "Yourpack_Weekend_Boost": { "Enabled": false } } // or switch one off
 *
 * // mods/ziggfreedcommon/boost-settings.json
 * { "Default": { "Cap": 300, "Limits": { "Personal": 5 } } }
 * }</pre>
 *
 * <p>Each is read from its store's own load event, after the packs have landed. A boost entry follows the mod gate
 * as a boost file does: one gated on a missing mod, or retuning a boost the gate kept out, is dropped.
 */
public final class BoostOwnerLayers {

    /** Where a server owner's files live. */
    public static final Path DEFAULT_DIRECTORY = OwnerFiles.DIRECTORY;

    /** The owner file over the boosts. */
    public static final String FILE = "boosts.json";

    /** The owner file over the server-wide settings. */
    public static final String SETTINGS_FILE = "boost-settings.json";

    private static final String LOG_TAG = "boost";

    @Nonnull
    private static volatile Path directory = DEFAULT_DIRECTORY;

    private BoostOwnerLayers() {
    }

    /** Point the owner files at another directory (a test, or a consumer with its own data dir). */
    public static void setDirectory(@Nonnull Path dir) {
        directory = dir;
    }

    @Nonnull
    public static Path directory() {
        return directory;
    }

    /** (Re)read {@code boosts.json} into the boost fold's owner layer. */
    public static void reload() {
        OwnerLayerReader.apply(LOG_TAG, directory.resolve(FILE), BoostAsset.class, BoostAsset.CODEC,
                BoostConfig.getInstance(), "boost", b -> GateSpec.missingMod(b.requires()));
    }

    /** (Re)read {@code boost-settings.json} into the settings fold's owner layer. */
    public static void reloadSettings() {
        OwnerLayerReader.apply(LOG_TAG, directory.resolve(SETTINGS_FILE), BoostSettingsAsset.class,
                BoostSettingsAsset.CODEC, BoostSettingsConfig.getInstance(), "boost settings");
    }
}
