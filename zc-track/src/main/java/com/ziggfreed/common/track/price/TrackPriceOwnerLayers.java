package com.ziggfreed.common.track.price;

import java.nio.file.Path;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.OwnerLayerReader;
import com.ziggfreed.common.util.OwnerFiles;

/**
 * The server owner's word on the XP price table, at {@code mods/ziggfreedcommon/track-prices.json}: a map from a
 * price id to the leaves that row should read differently (or a new row), decoded against the packs' own answer
 * through the same codec. The owner menu's XP Prices screen ({@code OwnerForms/Track_Prices.json}) writes it.
 *
 * <pre>{@code
 * { "Quest": { "Minutes": 20 },                        // every quest pays 20 minutes instead
 *   "Yourserver_Event": { "Minutes": 30, "MaxLevel": 50 } } // a row of your own
 * }</pre>
 *
 * <p>Read from the store's own load event, after the packs have landed.
 */
public final class TrackPriceOwnerLayers {

    /** The owner file. */
    public static final String FILE = "track-prices.json";

    /** The file's id as an owner form names it: its path under {@code mods/}. */
    public static final String BINDING = "ziggfreedcommon/" + FILE;

    @Nonnull
    private static volatile Path directory = OwnerFiles.DIRECTORY;

    private TrackPriceOwnerLayers() {
    }

    /** Point the owner file at another directory (a test, or a consumer with its own data dir). */
    public static void setDirectory(@Nonnull Path dir) {
        directory = dir;
    }

    /** Where the owner's file lives right now. */
    @Nonnull
    public static Path file() {
        return directory.resolve(FILE);
    }

    /** (Re)read {@code track-prices.json} into the price fold's owner layer. */
    public static void reload() {
        OwnerLayerReader.apply("track", file(), TrackPriceAsset.class, TrackPriceAsset.CODEC,
                TrackPriceConfig.getInstance(), "XP price");
    }
}
