package com.ziggfreed.common.track.pick;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.asset.OwnerLayerReader;
import com.ziggfreed.common.util.OwnerFiles;

/**
 * The {@code defaults < pack < owner} fold of every {@link TrackPickRespecAsset}, and its owner file
 * {@code mods/ziggfreedcommon/track-pick-respecs.json}: an id to the leaves that respec should read differently,
 * decoded against the packs' own answer through the same codec, so an owner changes a share, the fee or where it is
 * offered leaf by leaf.
 *
 * <pre>{@code
 * { "Your_Respec": { "Refund": { "Currencies": { "Your_Coin": 0.5 } },        // half back
 *                    "Cost": { "Currencies": { "Your_Coin": 100 } } } }
 * }</pre>
 *
 * <p>Read from the store's own load event, after the packs have landed. A respec is read when it is offered or
 * bought, so a merge moves nothing else. An owner form edits the file through its keyed binding ({@link #BINDING}).
 */
public final class TrackPickRespecConfig extends AbstractKeyedAssetConfig<TrackPickRespecAsset> {

    /** The owner file. */
    public static final String FILE = "track-pick-respecs.json";

    /** The owner file's id as an owner form names it (zc-core's {@code OwnerFileBindings}): its path under {@code mods/}. */
    public static final String BINDING = "ziggfreedcommon/" + FILE;

    private static final String LOG_TAG = "track";

    private static final TrackPickRespecConfig INSTANCE = new TrackPickRespecConfig();

    @Nonnull
    private static volatile Path directory = OwnerFiles.DIRECTORY;

    private TrackPickRespecConfig() {
    }

    @Nonnull
    public static TrackPickRespecConfig getInstance() {
        return INSTANCE;
    }

    /** Point the owner file at another directory (a test, or a consumer with its own data dir). */
    public static void setDirectory(@Nonnull Path dir) {
        directory = dir;
    }

    /** The owner file ({@value #FILE}). */
    @Nonnull
    public static Path file() {
        return directory.resolve(FILE);
    }

    /** (Re)read {@value #FILE} into the owner layer. */
    public static void reloadOwnerLayer() {
        OwnerLayerReader.apply(LOG_TAG, file(), TrackPickRespecAsset.class, TrackPickRespecAsset.CODEC, INSTANCE,
                "track pick respec");
    }

    /** The switched-on respec {@code id} names (any case), or null. */
    @Nullable
    public TrackPickRespecAsset live(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        TrackPickRespecAsset respec = resolve(id.trim());
        return respec == null || !respec.isEnabled() ? null : respec;
    }

    /** Every switched-on respec, by Order then id. */
    @Nonnull
    public List<TrackPickRespecAsset> liveRespecs() {
        List<TrackPickRespecAsset> out = new ArrayList<>();
        for (TrackPickRespecAsset respec : all().values()) {
            if (respec != null && respec.isEnabled()) {
                out.add(respec);
            }
        }
        out.sort(Comparator.comparingInt(TrackPickRespecAsset::order).thenComparing(TrackPickRespecAsset::getId,
                String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(out);
    }
}
