package com.ziggfreed.common.calendar.asset;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import javax.annotation.Nonnull;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ziggfreed.common.asset.OwnerLayerReader;
import com.ziggfreed.common.util.OwnerFiles;
import com.ziggfreed.common.util.SafeLog;

/**
 * The server owner's last word on the calendar, at {@code mods/ziggfreedcommon/calendar.json}:
 *
 * <pre>{@code
 * { "$Enabled": false,                                   // every event off: absent, not locked
 *   "Spring_Fair": { "Enabled": false },                 // or just one of them
 *   "Harvest_Feast": { "Window": { "End": "10-30" } } }  // or retune one, leaf by leaf
 * }</pre>
 *
 * <p>The global switch is {@code $}-prefixed because the shared reader treats every other top-level key
 * as an event id ({@link OwnerFiles}). A missing, malformed or newer-schema file puts both the switch
 * and the entries back to what the packs say, with one line naming the file.
 */
public final class CalendarOwnerLayers {

    /** Where a server owner's calendar file lives. */
    public static final Path DEFAULT_DIRECTORY = Paths.get("mods", "ziggfreedcommon");

    public static final String FILE = "calendar.json";

    /** The top-level key switching every event off at once. */
    public static final String GLOBAL_SWITCH = "$Enabled";

    private static final String LOG_TAG = "calendar";
    private static final String NOUN = "calendar event";

    @Nonnull
    private static volatile Path directory = DEFAULT_DIRECTORY;

    private CalendarOwnerLayers() {
    }

    /** Point the owner file at another directory (a test, or a consumer with its own data dir). */
    public static void setDirectory(@Nonnull Path dir) {
        directory = dir;
    }

    @Nonnull
    public static Path directory() {
        return directory;
    }

    /** (Re)read the file: the global switch, then each entry decoded against the pack layer's answer for its id. */
    public static void reload() {
        CalendarEventConfig config = CalendarEventConfig.getInstance();
        Path file = directory.resolve(FILE);
        JsonObject root = OwnerLayerReader.readObject(LOG_TAG, file);
        if (root == null || !OwnerFiles.schemaReadable(root, LOG_TAG, file)) {
            config.setGlobalEnabled(true);
            config.mergeOwnerLayer(Map.of());
            config.reportProblems();
            return;
        }
        config.setGlobalEnabled(globalSwitch(root, file));
        OwnerLayerReader.apply(LOG_TAG, file, CalendarEventAsset.class, CalendarEventAsset.CODEC, config, NOUN);
        config.reportProblems();
    }

    /** {@code $Enabled}: true when absent, and true (with a warning) when it is not a boolean. */
    static boolean globalSwitch(@Nonnull JsonObject root, @Nonnull Path file) {
        JsonElement value = root.get(GLOBAL_SWITCH);
        if (value == null) {
            return true;
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
            boolean on = value.getAsBoolean();
            if (!on) {
                SafeLog.info("[calendar] " + file + " switches every calendar event off");
            }
            return on;
        }
        SafeLog.warn("[calendar] " + file + ": " + GLOBAL_SWITCH + " is not true or false, so every calendar"
                + " event stays switched on");
        return true;
    }
}
