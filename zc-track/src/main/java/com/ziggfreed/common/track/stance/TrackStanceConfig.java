package com.ziggfreed.common.track.stance;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.asset.OwnerLayerReader;
import com.ziggfreed.common.util.OwnerFiles;

/**
 * The {@code defaults < pack < owner} fold of every {@link TrackStanceAsset}, and its owner file
 * {@code mods/ziggfreedcommon/track-stances.json}: an id to the leaves that stance should read differently, decoded
 * against the packs' own answer through the same codec, so an owner opens a third slot, raises a kind's cap or
 * changes a fee leaf by leaf.
 *
 * <pre>{@code
 * { "Your_Stance": { "Kinds": { "Weapon": { "Max": 2 } },          // two of a kind at once
 *                    "Break": { "Cost": { "Currencies": { "Your_Coin": 100 } } } } }
 * }</pre>
 *
 * <p>Read from the store's own load event, after the packs have landed. Every merge moves the stances
 * ({@link Stances#invalidate}): the gate epoch, and every online player's held faces settled and grants rebuilt. An
 * owner form edits the file through its keyed binding ({@link #BINDING}).
 */
public final class TrackStanceConfig extends AbstractKeyedAssetConfig<TrackStanceAsset> {

    /** The owner file. */
    public static final String FILE = "track-stances.json";

    /** The owner file's id as an owner form names it (zc-core's {@code OwnerFileBindings}): its path under {@code mods/}. */
    public static final String BINDING = "ziggfreedcommon/" + FILE;

    private static final String LOG_TAG = "track";

    private static final TrackStanceConfig INSTANCE = new TrackStanceConfig();

    @Nonnull
    private static volatile Path directory = OwnerFiles.DIRECTORY;

    private TrackStanceConfig() {
    }

    @Nonnull
    public static TrackStanceConfig getInstance() {
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
        OwnerLayerReader.apply(LOG_TAG, file(), TrackStanceAsset.class, TrackStanceAsset.CODEC, INSTANCE,
                "track stance");
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, TrackStanceAsset> layer) {
        super.mergePackLayer(layer);
        Stances.invalidate();
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, TrackStanceAsset> layer) {
        super.mergeOwnerLayer(layer);
        Stances.invalidate();
    }

    /** The switched-on stance {@code id} names (any case), or null. */
    @Nullable
    public TrackStanceAsset live(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        TrackStanceAsset stance = resolve(id.trim());
        return stance == null || !stance.isEnabled() ? null : stance;
    }

    /** Every switched-on stance, by Order then id. */
    @Nonnull
    public List<TrackStanceAsset> liveStances() {
        List<TrackStanceAsset> out = new ArrayList<>();
        for (TrackStanceAsset stance : all().values()) {
            if (stance != null && stance.isEnabled()) {
                out.add(stance);
            }
        }
        out.sort(Comparator.comparingInt(TrackStanceAsset::order).thenComparing(TrackStanceAsset::getId,
                String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(out);
    }
}
