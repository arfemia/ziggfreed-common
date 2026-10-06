package com.ziggfreed.common.almanac.asset;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ziggfreed.common.almanac.AlmanacSwitch;
import com.ziggfreed.common.almanac.page.AlmanacMenuTab;
import com.ziggfreed.common.asset.OwnerLayerReader;
import com.ziggfreed.common.util.JsonOverrideWriter;
import com.ziggfreed.common.util.OwnerFiles;
import com.ziggfreed.common.util.SafeLog;

/**
 * The SERVER OWNER's word on the Almanac, at {@code mods/ziggfreedcommon/almanac.json}: the
 * {@value #ENABLED_KEY} kill switch, the menu tab's knobs, plus a map from a season id to the page leaves
 * that season should read differently, decoded against the packs' own page through the same codec.
 *
 * <pre>{@code
 * // mods/ziggfreedcommon/almanac.json
 * { "$Enabled": true,
 *   "$MenuTab": { "Show": true, "OnlyWhileLive": false },
 *   "Spring_Fair": { "Stats": { "Kites_Flown": { "Order": 5 } } } }
 * }</pre>
 *
 * <p>The switch is a {@code $}-key because every top-level key without one is a season id. It is read
 * once at setup ({@link #readSwitch}) and again with the per-season layer whenever the pages load
 * ({@link #reload}). A missing file, a file that is not JSON, a switch that is not true or false, and a
 * file from a newer schema all leave the Almanac on.
 *
 * <p>{@code $MenuTab} says when the shared menu shows the Almanac tab: {@code Show} false hides it,
 * {@code OnlyWhileLive} true shows it only while a season runs; by default it shows whenever a season is
 * listed.
 */
public final class AlmanacOwnerLayers {

    /** Where a server owner's files live. */
    public static final Path DEFAULT_DIRECTORY = Paths.get("mods", "ziggfreedcommon");

    /** The owner file. */
    public static final String FILE = "almanac.json";

    /** The file-level switch. */
    public static final String ENABLED_KEY = "$Enabled";

    /** The file-level group holding the menu tab's knobs: a {@code $}-key, so it is never read as a season. */
    public static final String MENU_TAB_KEY = "$MenuTab";

    /** Whether the menu shows the Almanac tab at all. */
    public static final String SHOW_KEY = "Show";

    /** Whether it shows only while a season is running. */
    public static final String ONLY_WHILE_LIVE_KEY = "OnlyWhileLive";

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

    /** Read the file-level keys alone ({@code $Enabled} and {@code $MenuTab}): safe at setup, before any pack has loaded. */
    public static void readSwitch() {
        Path file = directory.resolve(FILE);
        JsonObject root = OwnerLayerReader.readObject(LOG_TAG, file);
        AlmanacSwitch.set(switchIn(root, file));
        AlmanacMenuTab.set(menuTabIn(root, file));
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

    /** What {@code root} says about the menu tab: each knob its own, a missing or malformed one at its default. */
    @Nonnull
    static AlmanacMenuTab.Knobs menuTabIn(@Nullable JsonObject root, @Nonnull Path file) {
        AlmanacMenuTab.Knobs defaults = AlmanacMenuTab.Knobs.DEFAULTS;
        if (root == null || !OwnerFiles.schemaReadable(root, LOG_TAG, file)) {
            return defaults;
        }
        JsonElement group = root.get(MENU_TAB_KEY);
        if (group == null) {
            return defaults;
        }
        if (!group.isJsonObject()) {
            SafeLog.warn("[" + LOG_TAG + "] " + file + ": " + MENU_TAB_KEY
                    + " must be a block of settings, so the Almanac tab keeps its defaults");
            return defaults;
        }
        JsonObject knobs = group.getAsJsonObject();
        return new AlmanacMenuTab.Knobs(booleanIn(knobs, SHOW_KEY, defaults.show(), file),
                booleanIn(knobs, ONLY_WHILE_LIVE_KEY, defaults.onlyWhileLive(), file));
    }

    private static boolean booleanIn(@Nonnull JsonObject group, @Nonnull String key, boolean fallback,
            @Nonnull Path file) {
        JsonElement value = group.get(key);
        if (value == null) {
            return fallback;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            SafeLog.warn("[" + LOG_TAG + "] " + file + ": " + MENU_TAB_KEY + "." + key
                    + " must be true or false, so it keeps its default");
            return fallback;
        }
        return value.getAsBoolean();
    }

    /** Has anybody written the menu tab's group into the owner file? */
    public static boolean menuTabWritten() {
        JsonObject root = OwnerLayerReader.readObject(LOG_TAG, directory.resolve(FILE));
        return root != null && root.has(MENU_TAB_KEY);
    }

    /**
     * Write both knobs into the owner file, every other key kept, and put them in force: a consumer's
     * settings page and a one-time move from an older setting come through here. True when the file was written.
     */
    public static boolean writeMenuTab(@Nonnull AlmanacMenuTab.Knobs knobs) {
        Map<String, Object> leaves = new LinkedHashMap<>();
        leaves.put(MENU_TAB_KEY + "." + SHOW_KEY, knobs.show());
        leaves.put(MENU_TAB_KEY + "." + ONLY_WHILE_LIVE_KEY, knobs.onlyWhileLive());
        boolean written = JsonOverrideWriter.setLeaves(directory.resolve(FILE), leaves);
        if (written) {
            AlmanacMenuTab.set(knobs);
        }
        return written;
    }
}
