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

import com.ziggfreed.common.util.OwnerFiles;
import com.ziggfreed.common.util.SafeLog;

/**
 * The server owner's last word on conversations, at {@code mods/ziggfreedcommon/dialogues.json}.
 *
 * <p>It exists so an admin can retune what a character says WITHOUT editing the pack that ships
 * them, which an update would overwrite. Like its sibling owner files in the same directory, it is
 * a map from an id to what that id should read differently:
 *
 * <pre>{@code
 * {
 *   "mmo_hub_intro": { "Nodes": { "greet": { "TextKey": "my.own.greeting" } } }
 * }
 * }</pre>
 *
 * <p><b>Override BY ID, LEAF BY LEAF.</b> An entry is folded over the stored conversation of that
 * id through the same per-node merge {@code Parent} inheritance uses: a screen, a memory or a
 * shared option group the entry does not restate keeps what the stored conversation gave it, and
 * {@code Start} is one ladder a body that writes one replaces. An id nothing stores is a new
 * conversation rather than an error, standing on its own. There is one schema whichever layer a
 * body was written in - the shape here is exactly a {@code Server/ZiggfreedCommon/Dialogues/} file's.
 *
 * <p><b>{@code $}-prefixed top-level keys are reserved, never entries</b> ({@link OwnerFiles}):
 * {@code $Comment} is documentation, and {@code $SchemaVersion} names the file's schema (absent
 * means 1, the shape above; a newer number than this library reads refuses the whole file with
 * one warning).
 *
 * <p>A malformed file is never overwritten and is treated as empty, so a hand-edit typo costs the
 * overrides rather than the file. A body that will not decode is reported by id and skipped,
 * leaving the stored conversation of that id standing.
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
            JsonObject declared = root.getAsJsonObject();
            if (!OwnerFiles.schemaReadable(declared, "dialogue", path)) {
                bodies = Map.of();
                return;
            }
            Map<String, JsonObject> read = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : declared.entrySet()) {
                if (OwnerFiles.isReservedKey(entry.getKey())) {
                    continue; // $Comment, $SchemaVersion and friends are file-level, not entries
                }
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
