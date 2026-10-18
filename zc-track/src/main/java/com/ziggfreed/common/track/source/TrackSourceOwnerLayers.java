package com.ziggfreed.common.track.source;

import java.nio.file.Path;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.OwnerLayerReader;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.util.OwnerFiles;

/**
 * The server owner's word on track sources, at {@code mods/ziggfreedcommon/track-sources.json}: a map from a
 * source id to the leaves that source should read differently, decoded against the packs' own answer through the
 * same codec.
 *
 * <pre>{@code
 * { "Your_Mining_Ores": { "Rows": { "Ore_Iron*": { "Xp": 40, "Level": 10 } } },   // retune one row
 *   "Your_Swords_Hits": { "Enabled": false } }                                    // or switch a source off
 * }</pre>
 *
 * <p>Read from the store's own load event, after the packs have landed. An entry follows the mod gate as a source
 * file does: one gated on a missing mod, or retuning a source the gate kept out, is dropped.
 */
public final class TrackSourceOwnerLayers {

    /** Where a server owner's file lives. */
    public static final Path DEFAULT_DIRECTORY = OwnerFiles.DIRECTORY;

    /** The owner file. */
    public static final String FILE = "track-sources.json";

    @Nonnull
    private static volatile Path directory = DEFAULT_DIRECTORY;

    private TrackSourceOwnerLayers() {
    }

    /** Point the owner file at another directory (a test, or a consumer with its own data dir). */
    public static void setDirectory(@Nonnull Path dir) {
        directory = dir;
    }

    /** (Re)read {@code track-sources.json} into the fold's owner layer. */
    public static void reload() {
        OwnerLayerReader.apply("track", directory.resolve(FILE), TrackSourceAsset.class, TrackSourceAsset.CODEC,
                TrackSourceConfig.getInstance(), "track source", s -> GateSpec.missingMod(s.requires()));
    }
}
