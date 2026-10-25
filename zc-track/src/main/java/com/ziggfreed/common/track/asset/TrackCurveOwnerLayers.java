package com.ziggfreed.common.track.asset;

import java.nio.file.Path;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.OwnerLayerReader;
import com.ziggfreed.common.util.OwnerFiles;

/**
 * The server owner's word on curves, at {@code mods/ziggfreedcommon/track-curves.json}: a map from a curve id to the
 * leaves that curve should read differently, decoded against the packs' own answer through the same codec (R573).
 *
 * <pre>{@code
 * { "Skill_Hybrid": { "Beyond": { "Every": 150000 } } }       // retune the shared curve, leaf by leaf
 * }</pre>
 *
 * <p>Read from the curve store's own load event, after the packs have landed. Each entry wins over what a consumer
 * hands the same layer from the owner's older settings ({@link TrackCurveConfig#ownerWord}), id by id.
 */
public final class TrackCurveOwnerLayers {

    /** Where a server owner's file lives. */
    public static final Path DEFAULT_DIRECTORY = OwnerFiles.DIRECTORY;

    /** The owner file. */
    public static final String FILE = "track-curves.json";

    private static final String LOG_TAG = "track";

    @Nonnull
    private static volatile Path directory = DEFAULT_DIRECTORY;

    private TrackCurveOwnerLayers() {
    }

    /** Point the owner file at another directory (a test, or a consumer with its own data dir). */
    public static void setDirectory(@Nonnull Path dir) {
        directory = dir;
    }

    @Nonnull
    public static Path directory() {
        return directory;
    }

    /** (Re)read {@code track-curves.json} into the curve fold's owner layer. */
    public static void reload() {
        OwnerLayerReader.apply(LOG_TAG, directory.resolve(FILE), TrackCurveAsset.class, TrackCurveAsset.CODEC,
                TrackCurveConfig.getInstance(), "curve");
    }
}
