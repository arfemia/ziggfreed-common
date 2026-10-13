package com.ziggfreed.common.playerstats;

import java.nio.file.Path;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonObject;
import com.ziggfreed.common.asset.OwnerLayerReader;
import com.ziggfreed.common.util.OwnerFiles;

/**
 * Whether the player stat store exists on this server: the {@value #ENABLED_KEY} key of the owner file
 * {@code mods/ziggfreedcommon/stats.json}, on until it says otherwise. Off means ABSENT, not locked:
 * nothing is counted or ranked, the {@code ziggfreedcommon:counter} reading answers nothing, and the
 * board shows no stats tab. What players already hold is kept, and a one-time import still runs, since
 * it moves saved data rather than counting anything.
 *
 * <p>This reads the switch alone; the file's other entries (period overrides, ranges) belong to the
 * board. A missing file, a file that is not JSON, a switch that is not true or false, and a file from a
 * newer schema all leave the store on.
 */
public final class StatsSwitch {

    /** Where a server owner's files live. */
    public static final Path DEFAULT_DIRECTORY = OwnerFiles.DIRECTORY;

    /** The owner file. */
    public static final String FILE = "stats.json";

    /** The file-level switch. */
    public static final String ENABLED_KEY = "$Enabled";

    private static final String LOG_TAG = "stats";

    @Nonnull
    private static volatile Path directory = DEFAULT_DIRECTORY;

    private static volatile boolean on = true;

    private StatsSwitch() {
    }

    /** Is the stat store switched on right now? */
    public static boolean isOn() {
        return on;
    }

    /** Read the owner file's switch. Safe at setup and again whenever the stat files load. */
    public static void reload() {
        Path file = directory.resolve(FILE);
        on = switchIn(OwnerLayerReader.readObject(LOG_TAG, file), file);
    }

    /** What {@code root} says about the switch: on unless it says false in so many words. */
    static boolean switchIn(@Nullable JsonObject root, @Nonnull Path file) {
        return OwnerFiles.flag(root, ENABLED_KEY, file, LOG_TAG,
                "must be true or false, so the stat store stays on", null);
    }

    /** Point the owner file at a different directory (a test). */
    public static void setDirectory(@Nonnull Path dir) {
        directory = dir;
    }
}
