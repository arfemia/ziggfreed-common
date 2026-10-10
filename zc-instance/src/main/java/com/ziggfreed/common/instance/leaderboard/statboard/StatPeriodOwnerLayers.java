package com.ziggfreed.common.instance.leaderboard.statboard;

import java.nio.file.Path;
import java.nio.file.Paths;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.OwnerLayerReader;

/**
 * The server owner's word on the stat periods, in {@code mods/ziggfreedcommon/stats.json}: each top-level
 * entry is a period id and the leaves it should read differently, decoded against the packs' own answer
 * through the same codec, and a new id is a period of the owner's own (a range, say).
 *
 * <pre>{@code
 * // mods/ziggfreedcommon/stats.json
 * { "$Enabled": true,
 *   "Weekly": { "Zone": "America/New_York", "StartsOn": "Sunday" },
 *   "Launch": { "Kind": "Range", "NameKey": "myserver.stats.period.launch",
 *               "From": "2026-10-12T00:00", "To": "2026-10-19T00:00", "Order": 40 } }
 * }</pre>
 *
 * <p>The file is the stat store's too: a {@code $}-prefixed key such as {@code $Enabled} is the store's,
 * reserved and never read here as a period. Read from the period store's own load event, since an owner
 * entry has nothing to inherit from until the packs have landed.
 */
public final class StatPeriodOwnerLayers {

    /** Where a server owner's files live. */
    public static final Path DEFAULT_DIRECTORY = Paths.get("mods", "ziggfreedcommon");

    /** The owner file the periods are read from, shared with the stat store. */
    public static final String FILE = "stats.json";

    private static final String LOG_TAG = "statboard";

    @Nonnull
    private static volatile Path directory = DEFAULT_DIRECTORY;

    private StatPeriodOwnerLayers() {
    }

    /** Point the owner file at a different directory (a test, or a consumer with its own data dir). */
    public static void setDirectory(@Nonnull Path dir) {
        directory = dir;
    }

    /** Where the owner file and the stat board's own file are read from. */
    @Nonnull
    public static Path directory() {
        return directory;
    }

    /** (Re)read {@code stats.json}'s periods into the period fold's owner layer. */
    public static void reload() {
        OwnerLayerReader.apply(LOG_TAG, directory.resolve(FILE), StatPeriodAsset.class, StatPeriodAsset.CODEC,
                StatPeriodConfig.getInstance(), "stat period");
    }
}
