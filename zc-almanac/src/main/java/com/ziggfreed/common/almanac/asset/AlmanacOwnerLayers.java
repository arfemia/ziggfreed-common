package com.ziggfreed.common.almanac.asset;

import java.nio.file.Path;
import java.nio.file.Paths;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ziggfreed.common.almanac.AlmanacSwitch;
import com.ziggfreed.common.asset.OwnerLayerReader;
import com.ziggfreed.common.util.OwnerFiles;
import com.ziggfreed.common.util.SafeLog;

/**
 * The SERVER OWNER's word on the Almanac, at {@code mods/ziggfreedcommon/almanac.json}: the
 * {@value #ENABLED_KEY} kill switch, plus a map from a season id to the page leaves that season should
 * read differently, decoded against the packs' own page through the same codec.
 *
 * <pre>{@code
 * // mods/ziggfreedcommon/almanac.json
 * { "$Enabled": true,
 *   "Hallows_Eve": { "Stats": { "Bombs_Thrown": { "Order": 5 } } } }
 * }</pre>
 *
 * <p>The switch is a {@code $}-key because every top-level key without one is a season id. It is read
 * once at setup ({@link #readSwitch}) and again with the per-season layer whenever the pages load
 * ({@link #reload}). A missing file, a file that is not JSON, a switch that is not true or false, and a
 * file from a newer schema all leave the Almanac on.
 */
public final class AlmanacOwnerLayers {

    /** Where a server owner's files live. */
    public static final Path DEFAULT_DIRECTORY = Paths.get("mods", "ziggfreedcommon");

    /** The owner file. */
    public static final String FILE = "almanac.json";

    /** The file-level switch. */
    public static final String ENABLED_KEY = "$Enabled";

    private static final String LOG_TAG = "almanac";

    @Nonnull
    private static volatile Path directory = DEFAULT_DIRECTORY;

    private AlmanacOwnerLayers() {
    }

    /** Point the owner file at a different directory (a test, or a consumer with its own data dir). */
    public static void setDirectory(@Nonnull Path dir) {
        directory = dir;
    }

    /** Where the owner file is being read from. */
    @Nonnull
    public static Path directory() {
        return directory;
    }

    /** Read the switch alone: safe at setup, before any pack has loaded. */
    public static void readSwitch() {
        Path file = directory.resolve(FILE);
        AlmanacSwitch.set(switchIn(OwnerLayerReader.readObject(LOG_TAG, file), file));
    }

    /** Re-read the whole file: the switch, then the per-season layer over the packs' pages. */
    public static void reload() {
        readSwitch();
        OwnerLayerReader.apply(LOG_TAG, directory.resolve(FILE), AlmanacEntryAsset.class,
                AlmanacEntryAsset.CODEC, AlmanacEntryConfig.getInstance(), "Almanac page");
    }

    /** What {@code root} says about the switch: on unless it says false in so many words. */
    static boolean switchIn(@Nullable JsonObject root, @Nonnull Path file) {
        if (root == null || !OwnerFiles.schemaReadable(root, LOG_TAG, file)) {
            return true;
        }
        JsonElement value = root.get(ENABLED_KEY);
        if (value == null) {
            return true;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            SafeLog.warn("[" + LOG_TAG + "] " + file + ": " + ENABLED_KEY
                    + " must be true or false, so the Almanac stays on");
            return true;
        }
        return value.getAsBoolean();
    }
}
