package com.ziggfreed.common.reputation.asset;

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
 * The server owner's word on reputations, at {@code mods/ziggfreedcommon/reputation.json}:
 *
 * <pre>{@code
 * { "$Enabled": false,                                  // every reputation off: absent, not locked
 *   "Hallows_Eve_Old_Jack": { "Cap": 9000 },            // or retune one, leaf by leaf
 *   "Hallows_Eve_Hallowed": { "Enabled": false } }      // or switch one off
 * }</pre>
 *
 * <p>The switch is a {@code $}-key because every other top-level key is a reputation id. It is read on its
 * own at setup ({@link #readSwitch}), since a server whose packs ship no companion file never loads the
 * store, and again with the entries whenever the companions load ({@link #reload}). A missing file, a file
 * that is not JSON, a switch that is not true or false and a newer-schema file all leave the module on.
 */
public final class ReputationOwnerLayers {

    /** Where a server owner's files live. */
    public static final Path DEFAULT_DIRECTORY = Paths.get("mods", "ziggfreedcommon");

    /** The owner file. */
    public static final String FILE = "reputation.json";

    /** The file-level switch over the whole module. */
    public static final String GLOBAL_SWITCH = "$Enabled";

    private static final String LOG_TAG = "reputation";
    private static final String NOUN = "reputation";

    @Nonnull
    private static volatile Path directory = DEFAULT_DIRECTORY;

    private ReputationOwnerLayers() {
    }

    /** Point the owner file at another directory (a test, or a consumer with its own data dir). */
    public static void setDirectory(@Nonnull Path dir) {
        directory = dir;
    }

    @Nonnull
    public static Path directory() {
        return directory;
    }

    /** Read the switch alone: safe at setup, before any pack has loaded. */
    public static void readSwitch() {
        Path file = directory.resolve(FILE);
        ReputationConfig.getInstance().setGlobalEnabled(switchIn(OwnerLayerReader.readObject(LOG_TAG, file), file));
    }

    /** Re-read the whole file: the switch, then each entry over the packs' own answer for its id. */
    public static void reload() {
        readSwitch();
        OwnerLayerReader.apply(LOG_TAG, directory.resolve(FILE), ReputationAsset.class, ReputationAsset.CODEC,
                ReputationConfig.getInstance(), NOUN);
    }

    /** What {@code root} says about the switch: on unless it says false in so many words. */
    static boolean switchIn(@Nullable JsonObject root, @Nonnull Path file) {
        if (root == null || !OwnerFiles.schemaReadable(root, LOG_TAG, file)) {
            return true;
        }
        JsonElement value = root.get(GLOBAL_SWITCH);
        if (value == null) {
            return true;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            SafeLog.warn("[reputation] " + file + ": " + GLOBAL_SWITCH
                    + " must be true or false, so reputations stay on");
            return true;
        }
        boolean on = value.getAsBoolean();
        if (!on) {
            SafeLog.info("[reputation] " + file + " switches every reputation off");
        }
        return on;
    }
}
