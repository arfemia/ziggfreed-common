package com.ziggfreed.common.dialogue.asset;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.ziggfreed.common.util.SafeLog;

/**
 * The server owner's own conversations, at {@code mods/ziggfreedcommon/dialogues.json}.
 *
 * <p>It exists so an admin can rewrite what a character says WITHOUT editing the pack that ships
 * them, which an update would overwrite. An entry REPLACES the stored conversation of that id
 * outright: {@code Parent} inheritance belongs to the asset files, so a body written here stands on
 * its own and is not merged with the one it replaces.
 *
 * <pre>{@code
 * {
 *   "dialogues": {
 *     "mmo_hub_intro": { "Start": { "Fallback": "greet" }, "Nodes": { ... } }
 *   }
 * }
 * }</pre>
 *
 * <p>A body here is the SAME shape a file under {@code Server/ZiggfreedCommon/Dialogues/} carries and
 * is read by the same codec, so there is one schema whichever layer a conversation was written in.
 *
 * <p>A malformed file is never overwritten and is treated as empty, so a hand-edit typo costs the
 * overrides rather than the file. A body that will not decode is reported by id and skipped, leaving
 * the stored conversation of that id standing.
 */
public final class DialogueOverrides {

    private static final DialogueOverrides INSTANCE = new DialogueOverrides();

    @Nonnull
    public static DialogueOverrides getInstance() {
        return INSTANCE;
    }

    @Nonnull
    private volatile Path file = Paths.get("mods", "ziggfreedcommon", "dialogues.json");

    /** Immutable snapshot: lower-cased id to the authored body. */
    @Nonnull
    private volatile Map<String, JsonObject> bodies = Map.of();

    private DialogueOverrides() {
    }

    /** Point at a different file (tests, a relocated data dir). Reload afterwards to pick it up. */
    public void setFile(@Nonnull Path path) {
        this.file = path;
    }

    /** The authored bodies, by lower-cased id. Empty when there is no file or it would not parse. */
    @Nonnull
    public Map<String, JsonObject> bodies() {
        return bodies;
    }

    /**
     * Re-read the file. Absent is the ordinary case and costs nothing; malformed is reported once
     * and leaves the previous snapshot in place rather than dropping every override on a typo.
     */
    public synchronized void reload() {
        Path path = file;
        if (!Files.isRegularFile(path)) {
            bodies = Map.of();
            return;
        }
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(text);
            if (root == null || !root.isJsonObject()) {
                SafeLog.warn("[dialogue] " + path + " is not a JSON object, so no owner conversation is read");
                return;
            }
            JsonElement declared = root.getAsJsonObject().get("dialogues");
            if (declared == null || !declared.isJsonObject()) {
                bodies = Map.of();
                return;
            }
            Map<String, JsonObject> read = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : declared.getAsJsonObject().entrySet()) {
                if (entry.getValue() != null && entry.getValue().isJsonObject()) {
                    read.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue().getAsJsonObject());
                }
            }
            bodies = Collections.unmodifiableMap(read);
            if (!read.isEmpty()) {
                SafeLog.info("[dialogue] " + read.size() + " owner conversation(s) read from " + path);
            }
        } catch (Throwable t) {
            SafeLog.warn("[dialogue] " + path + " could not be read, so the owner conversations already"
                    + " in hand stand: " + t.getMessage());
        }
    }

    /** Forget the snapshot, which is the unloaded state a test starts from. */
    public void resetForTests() {
        bodies = Map.of();
    }

    @Nullable
    JsonObject body(@Nullable String id) {
        return id == null ? null : bodies.get(id.toLowerCase(Locale.ROOT));
    }
}
