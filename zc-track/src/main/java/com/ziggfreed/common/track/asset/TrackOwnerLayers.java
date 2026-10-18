package com.ziggfreed.common.track.asset;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.OwnerLayerReader;
import com.ziggfreed.common.factor.ModGates;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.progress.gate.StoreGates;
import com.ziggfreed.common.util.OwnerFiles;

/**
 * The server owner's word on tracks, at {@code mods/ziggfreedcommon/tracks.json}: a map from a track id to the
 * leaves that track should read differently, decoded against the packs' own answer through the same codec.
 *
 * <pre>{@code
 * { "Zc_Track_Example": { "Enabled": true },          // switch the library's sample track on
 *   "Yourmod_Mining": { "Cap": 99 } }                 // or retune one, leaf by leaf
 * }</pre>
 *
 * <p>Read from the store's own load event, after the packs have landed. An entry follows the mod gate as a
 * track file does: one gated on a missing mod, or retuning a track the gate kept out, is dropped. The read then
 * counts the unlock reward rows the mod gate leaves out of every folded track, in the store's one row line per
 * missing mod, and names once the tracks whose rows gate on more than mod presence.
 */
public final class TrackOwnerLayers {

    /** Where a server owner's file lives. */
    public static final Path DEFAULT_DIRECTORY = OwnerFiles.DIRECTORY;

    /** The owner file. */
    public static final String FILE = "tracks.json";

    private static final String LOG_TAG = "track";

    @Nonnull
    private static volatile Path directory = DEFAULT_DIRECTORY;

    private TrackOwnerLayers() {
    }

    /** Point the owner file at another directory (a test, or a consumer with its own data dir). */
    public static void setDirectory(@Nonnull Path dir) {
        directory = dir;
    }

    @Nonnull
    public static Path directory() {
        return directory;
    }

    /** (Re)read {@code tracks.json} into the fold's owner layer, then count the gated unlock rows. */
    public static void reload() {
        OwnerLayerReader.apply(LOG_TAG, directory.resolve(FILE), TrackAsset.class, TrackAsset.CODEC,
                TrackConfig.getInstance(), "track", t -> GateSpec.missingMod(t.requires()));
        List<String> gatedRows = new ArrayList<>();
        List<String> widened = new ArrayList<>();
        for (Map.Entry<String, TrackAsset> entry : TrackConfig.getInstance().all().entrySet()) {
            entry.getValue().collectGatedRows(gatedRows);
            if (entry.getValue().widensRows()) {
                widened.add(entry.getKey());
            }
        }
        ModGates.reportRewardRows(TrackConfig.MOD_GATE_STORE, gatedRows);
        StoreGates.reportWidened(TrackConfig.MOD_GATE_STORE + " unlock rows", widened);
    }
}
