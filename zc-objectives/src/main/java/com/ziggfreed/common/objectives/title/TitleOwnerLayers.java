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
 * {@value #AUTO_SHOW_KEY} and {@value #CHAT_KEY} switches, plus a map from a title id to the leaves it
 * should read differently, decoded against the packs' own answer through the same codec, like every
 * owner file this library reads.
 *
 * <pre>{@code
 * { "$AutoShow": true,
 *   "$Chat": false,
 *   "Example_Title": { "Enabled": false } }
 * }</pre>
 *
 * <p>{@value #AUTO_SHOW_KEY} says whether a title a player earns shows itself when they show none and
 * never chose to show none (R114, R214). A missing file, a file that is not JSON, a switch that is not
 * true or false, and a file from a newer schema all leave it on.
 *
 * <p>{@value #CHAT_KEY} says whether a chat line names its sender with their title ({@link TitleChat},
 * R55). The same cases all leave it off.
 *
 * <p>Both are {@code $}-keys because every top-level key without one is a title id. Read from the
 * store's own load event, since an owner entry has nothing to inherit from until the packs have
 * landed; each switch is then a volatile read.
 */
public final class TitleOwnerLayers {

    /** Where a server owner's files live. */
    public static final Path DEFAULT_DIRECTORY = Paths.get("mods", "ziggfreedcommon");

    /** The owner file over the titles. */
    public static final String TITLES_FILE = "titles.json";

    /** The file-level switch for a new title showing itself. */
    public static final String AUTO_SHOW_KEY = "$AutoShow";

    /** The file-level switch for a title in chat. */
    public static final String CHAT_KEY = "$Chat";

    private static final String LOG_TAG = "title";

    @Nonnull
    private static volatile Path directory = DEFAULT_DIRECTORY;

    private static volatile boolean autoShow = true;

    private static volatile boolean chat = false;

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

    /** Whether a chat line names its sender with their title ({@value #CHAT_KEY}); off by default. */
    public static boolean chat() {
        return chat;
    }

    /** (Re)read {@code titles.json}: the switches, then the fold's owner layer. */
    public static void reload() {
        Path file = directory.resolve(TITLES_FILE);
        JsonObject root = OwnerLayerReader.readObject(LOG_TAG, file);
        JsonObject readable = root != null && OwnerFiles.schemaReadable(root, LOG_TAG, file) ? root : null;
        autoShow = autoShowIn(readable, file);
        chat = chatIn(readable, file);
        OwnerLayerReader.apply(LOG_TAG, file, TitleAsset.class, TitleAsset.CODEC, TitleConfig.getInstance(), "title");
    }

    /**
     * What {@code root} (a file of a schema this library reads, or null) says about
     * {@value #AUTO_SHOW_KEY}: on unless it says false in so many words.
     */
    static boolean autoShowIn(@Nullable JsonObject root, @Nonnull Path file) {
        return switchIn(root, file, AUTO_SHOW_KEY, true, "so a new title still shows itself");
    }

    /**
     * What {@code root} (a file of a schema this library reads, or null) says about {@value #CHAT_KEY}:
     * off unless it says true in so many words.
     */
    static boolean chatIn(@Nullable JsonObject root, @Nonnull Path file) {
        return switchIn(root, file, CHAT_KEY, false, "so chat names players without their titles");
    }

    /** A file-level true-or-false key: {@code fallback} when absent, and with one warning when not a boolean. */
    private static boolean switchIn(@Nullable JsonObject root, @Nonnull Path file, @Nonnull String key,
            boolean fallback, @Nonnull String whenBad) {
        if (root == null) {
            return fallback;
        }
        JsonElement value = root.get(key);
        if (value == null) {
            return fallback;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            SafeLog.warn("[" + LOG_TAG + "] " + file + ": " + key + " must be true or false, " + whenBad);
            return fallback;
        }
        return value.getAsBoolean();
    }
}
