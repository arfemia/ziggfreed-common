package com.ziggfreed.common.track.pick;

import java.nio.file.Path;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.OwnerLayerReader;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.util.OwnerFiles;

/**
 * The server owner's word on picks and their options, at {@code mods/ziggfreedcommon/track-picks.json} and
 * {@code mods/ziggfreedcommon/track-pick-options.json}: each a map from an id to the leaves that file should read
 * differently, decoded against the packs' own answer through the same codec.
 *
 * <pre>{@code
 * track-picks.json:        { "Your_Swords_Lines":                             // keep a choice two levels apart
 *                                { "Groups": [ { "From": 2, "Every": 2, "Options": [ "Your_Edge", "Your_Keen" ] } ] },
 *                            "Your_Trainer_Whirlwind":                        // or take a trainer's pick at home
 *                                { "Where": { "Placements": [] } } }
 * track-pick-options.json: { "Your_Edge": { "Enabled": false } }              // or keep an option out of every offer
 * }</pre>
 *
 * <p>Read from each store's own load event, after the packs have landed. A pick entry follows the mod gate as a pick
 * file does: one gated on a missing mod, or retuning a pick the gate kept out, is dropped.
 */
public final class TrackPickOwnerLayers {

    /** Where a server owner's files live. */
    public static final Path DEFAULT_DIRECTORY = OwnerFiles.DIRECTORY;

    /** The owner file for picks. */
    public static final String FILE = "track-picks.json";

    /** The owner file for options. */
    public static final String OPTIONS_FILE = "track-pick-options.json";

    private static final String LOG_TAG = "track";

    @Nonnull
    private static volatile Path directory = DEFAULT_DIRECTORY;

    private TrackPickOwnerLayers() {
    }

    /** Point the owner files at another directory (a test, or a consumer with its own data dir). */
    public static void setDirectory(@Nonnull Path dir) {
        directory = dir;
    }

    /** (Re)read {@code track-picks.json} into the picks' owner layer. */
    public static void reload() {
        OwnerLayerReader.apply(LOG_TAG, directory.resolve(FILE), TrackPickAsset.class, TrackPickAsset.CODEC,
                TrackPickConfig.getInstance(), "track pick", p -> GateSpec.missingMod(p.requires()));
    }

    /** (Re)read {@code track-pick-options.json} into the options' owner layer. */
    public static void reloadOptions() {
        OwnerLayerReader.apply(LOG_TAG, directory.resolve(OPTIONS_FILE), PickOptionAsset.class,
                PickOptionAsset.CODEC, PickOptionConfig.getInstance(), "track pick option");
    }
}
