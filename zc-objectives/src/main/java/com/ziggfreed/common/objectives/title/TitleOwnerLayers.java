package com.ziggfreed.common.objectives.title;

import java.nio.file.Path;
import java.nio.file.Paths;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ziggfreed.common.asset.OwnerLayerReader;
import com.ziggfreed.common.util.OwnerFiles;
import com.ziggfreed.common.util.SafeLog;

/**
 * The SERVER OWNER's last word on titles, at {@code mods/ziggfreedcommon/titles.json}: the
 * {@value #AUTO_SHOW_KEY} switch, plus a map from a title id to the leaves it should read differently,
 * decoded against the packs' own answer through the same codec, like every owner file this library
 * reads.
 *
 * <pre>{@code
 * { "$AutoShow": true,
 *   "Example_Title": { "Enabled": false } }
 * }</pre>
 *
 * <p>{@value #AUTO_SHOW_KEY} says whether a title a player earns shows itself when they show none and
 * never chose to show none (R114, R214). It is a {@code $}-key because every top-level key without one
 * is a title id. A missing file, a file that is not JSON, a switch that is not true or false, and a file
 * from a newer schema all leave it on.
 *
 * <p>Read from the store's own load event, since an owner entry has nothing to inherit from until
 * the packs have landed.
 */
public final class TitleOwnerLayers {

    /** Where a server owner's files live. */
    public static final Path DEFAULT_DIRECTORY = Paths.get("mods", "ziggfreedcommon");

    /** The owner file over the titles. */
    public static final String TITLES_FILE = "titles.json";

    /** The file-level switch for a new title showing itself. */
    public static final String AUTO_SHOW_KEY = "$AutoShow";

    private static final String LOG_TAG = "title";

    @Nonnull
    private static volatile Path directory = DEFAULT_DIRECTORY;

    private static volatile boolean autoShow = true;

    private TitleOwnerLayers() {
    }

    /** Point the owner file at another directory (a test, or a consumer with its own data dir). */
    public static void setDirectory(@Nonnull Path dir) {
        directory = dir;
    }

    /** Where the owner file is read from. */
    @Nonnull
    public static Path directory() {
        return directory;
    }

    /** Whether a new title shows itself for a player showing none ({@value #AUTO_SHOW_KEY}); on by default. */
    public static boolean autoShow() {
        return autoShow;
    }

    /** (Re)read {@code titles.json}: the switch, then the fold's owner layer. */
    public static void reload() {
        Path file = directory.resolve(TITLES_FILE);
        autoShow = autoShowIn(OwnerLayerReader.readObject(LOG_TAG, file), file);
        OwnerLayerReader.apply(LOG_TAG, file, TitleAsset.class, TitleAsset.CODEC, TitleConfig.getInstance(), "title");
    }

    /** What {@code root} says about {@value #AUTO_SHOW_KEY}: on unless it says false in so many words. */
    static boolean autoShowIn(@Nullable JsonObject root, @Nonnull Path file) {
        if (root == null || !OwnerFiles.schemaReadable(root, LOG_TAG, file)) {
            return true;
        }
        JsonElement value = root.get(AUTO_SHOW_KEY);
        if (value == null) {
            return true;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            SafeLog.warn("[" + LOG_TAG + "] " + file + ": " + AUTO_SHOW_KEY
                    + " must be true or false, so a new title still shows itself");
            return true;
        }
        return value.getAsBoolean();
    }
}
