package com.ziggfreed.common.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.hypixel.hytale.logger.HytaleLogger;

/**
 * Partial-override write-back for a codec-authored owner file (the counterpart to a read/fold config
 * that has no {@code save()} of its own). Reads an existing override JSON object, sets or removes one or
 * more leaves addressed by DOTTED PascalCase codec paths (e.g. {@code "ZoneHud.Position"},
 * {@code "Difficulty.DistanceEscalation.Enabled"}) - preserving every sibling key and any leading
 * {@code $Comment} - then writes it back atomically. Also does the ONE thing
 * {@link JsonTreeUtil#deepMergeInto} cannot: a top-level ARRAY upsert/remove keyed by a match field
 * (deepMergeInto replaces arrays wholesale, the footgun), so a single element is replaced whole (or
 * appended / removed) while the other elements survive - the shape an owner-over-jar concat fold needs.
 *
 * <p><b>Type fidelity is the caller's contract.</b> A value's boxed type controls the emitted JSON
 * number form, which must match the target codec: pass an {@link Integer} for a {@code Codec.INTEGER}
 * leaf (emits {@code 16}), a {@link Double} for a {@code Codec.DOUBLE} leaf (emits {@code 1.5} /
 * {@code 2.0}), a {@link Boolean}, or a {@link String}. A {@code null} value REMOVES the leaf. Values in
 * a passed map may therefore be null, so pass a null-tolerant map ({@code LinkedHashMap}, not
 * {@code Map.of}); insertion order is preserved in the emitted object.
 *
 * <p>Gson-only + {@code java.nio}; the only reason it is not pure like {@link JsonTreeUtil} is a guarded
 * logger. Every method is fully guarded and returns {@code false} on any IO / parse failure (a malformed
 * existing file is NEVER overwritten - the write fails safe and the file is left intact), never throwing
 * into the caller. The public API is plain-Java-typed (String paths, boxed scalars, {@code Map}/
 * {@code List}) so a consumer mod calls it without a Gson dependency of its own.
 */
public final class JsonOverrideWriter {

    /**
     * This util's OWN guarded logger (NOT {@code ZiggfreedCommonPlugin.LOGGER}, which is unloadable in a
     * plain unit JVM via the JavaPlugin static-init chain; this class is unit-tested). Null in a
     * log-manager-less JVM; {@link #warn} null-checks it.
     */
    @Nullable private static final HytaleLogger LOGGER = initLogger();

    /** Pretty-printed, HTML-escaping OFF so patterns / comments stay human-readable in the owner file. */
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private JsonOverrideWriter() {
    }

    @Nullable
    private static HytaleLogger initLogger() {
        try {
            return HytaleLogger.forEnclosingClass();
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // Scalar / nested-object leaves
    // ---------------------------------------------------------------------

    /** Set (or, when {@code value} is null, remove) one dotted-PascalCase leaf. See {@link #setLeaves}. */
    public static boolean setLeaf(@Nonnull Path file, @Nonnull String dottedPath, @Nullable Object value) {
        return setLeaves(file, Collections.singletonMap(dottedPath, value));
    }

    /**
     * Set / remove several dotted-PascalCase leaves in ONE read+write. Non-null values are folded in via
     * {@link JsonTreeUtil#deepMergeInto} (nested objects merge, siblings + {@code $Comment} survive);
     * null values remove their leaf. Returns {@code false} on any failure (file left intact).
     */
    public static boolean setLeaves(@Nonnull Path file, @Nonnull Map<String, Object> leaves) {
        try {
            JsonObject root = readRoot(file);
            JsonObject patch = new JsonObject();
            List<String> removals = new ArrayList<>();
            for (Map.Entry<String, Object> e : leaves.entrySet()) {
                if (e.getValue() == null) {
                    removals.add(e.getKey());
                } else {
                    setInto(patch, e.getKey(), toPrimitive(e.getValue()));
                }
            }
            JsonTreeUtil.deepMergeInto(root, patch);
            for (String path : removals) {
                removeInto(root, path);
            }
            return write(file, root);
        } catch (Exception ex) {
            warn("setLeaves failed for " + file + ": " + ex.getMessage());
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Top-level array-by-key (whole-element replace / append / remove)
    // ---------------------------------------------------------------------

    /**
     * Upsert one WHOLE element into a top-level array, keyed by {@code matchField} (compared trimmed +
     * case-insensitive). The element is (re)built from {@code entryLeaves} (dotted PascalCase leaves,
     * null values skipped) with the match field guaranteed present + first; an existing element with the
     * same match value is REPLACED WHOLE (keeping that element's ORIGINAL match-field casing, so a
     * case-different upsert never silently rewrites the stored key), else a new element carrying
     * {@code matchValue} is appended. All OTHER elements are preserved. This is the persistence unit for a
     * concat-across-layers fold that dedups by the match field (owner wins), so writing one entry
     * augments rather than clobbers the array.
     */
    public static boolean upsertArrayEntry(@Nonnull Path file, @Nonnull String arrayKey,
            @Nonnull String matchField, @Nonnull String matchValue, @Nonnull Map<String, Object> entryLeaves) {
        try {
            JsonObject root = readRoot(file);
            JsonArray arr = arrayOf(root, arrayKey);
            int idx = indexOfMatch(arr, matchField, matchValue);
            // On REPLACE, keep the existing element's match-field value so a case-different upsert does not
            // silently rewrite the stored key's casing; on APPEND, use the caller's match value.
            String canonicalMatch = matchValue;
            if (idx >= 0 && arr.get(idx).isJsonObject()) {
                JsonElement m = arr.get(idx).getAsJsonObject().get(matchField);
                if (m != null && m.isJsonPrimitive()) {
                    canonicalMatch = m.getAsString();
                }
            }
            JsonObject entry = new JsonObject();
            entry.addProperty(matchField, canonicalMatch); // match key first + guaranteed present
            for (Map.Entry<String, Object> e : entryLeaves.entrySet()) {
                if (e.getValue() == null || e.getKey().equals(matchField)) {
                    continue;
                }
                setInto(entry, e.getKey(), toPrimitive(e.getValue()));
            }
            if (idx >= 0) {
                arr.set(idx, entry);
            } else {
                arr.add(entry);
            }
            root.add(arrayKey, arr); // in case the array was newly created
            return write(file, root);
        } catch (Exception ex) {
            warn("upsertArrayEntry failed for " + file + ": " + ex.getMessage());
            return false;
        }
    }

    /**
     * Remove the element of a top-level array whose {@code matchField} equals {@code matchValue}
     * (trimmed + case-insensitive), preserving the others. Idempotent: absent array / absent element is
     * treated as success ({@code true}).
     */
    public static boolean removeArrayEntry(@Nonnull Path file, @Nonnull String arrayKey,
            @Nonnull String matchField, @Nonnull String matchValue) {
        try {
            JsonObject root = readRoot(file);
            if (!root.has(arrayKey) || !root.get(arrayKey).isJsonArray()) {
                return true;
            }
            JsonArray arr = root.getAsJsonArray(arrayKey);
            int idx = indexOfMatch(arr, matchField, matchValue);
            if (idx < 0) {
                return true;
            }
            arr.remove(idx);
            root.add(arrayKey, arr);
            return write(file, root);
        } catch (Exception ex) {
            warn("removeArrayEntry failed for " + file + ": " + ex.getMessage());
            return false;
        }
    }

    /**
     * The {@code field} value of every object element of a top-level array (e.g. every {@code Match} in
     * {@code WorldOverrides}), in file order. Empty on absent array / any failure. Used to badge which
     * entries the owner file itself authored (vs jar/pack defaults).
     */
    @Nonnull
    public static List<String> readArrayKeyValues(@Nonnull Path file, @Nonnull String arrayKey,
            @Nonnull String field) {
        List<String> out = new ArrayList<>();
        try {
            JsonObject root = readRoot(file);
            if (!root.has(arrayKey) || !root.get(arrayKey).isJsonArray()) {
                return out;
            }
            for (JsonElement el : root.getAsJsonArray(arrayKey)) {
                if (el.isJsonObject()) {
                    JsonElement v = el.getAsJsonObject().get(field);
                    if (v != null && v.isJsonPrimitive()) {
                        out.add(v.getAsString());
                    }
                }
            }
        } catch (Exception ex) {
            warn("readArrayKeyValues failed for " + file + ": " + ex.getMessage());
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    /** Read the override root; empty object on missing/blank; throws on malformed (so we never clobber it). */
    @Nonnull
    private static JsonObject readRoot(@Nonnull Path file) throws IOException {
        if (!Files.exists(file)) {
            return new JsonObject();
        }
        String body = Files.readString(file, StandardCharsets.UTF_8);
        if (body.isBlank()) {
            return new JsonObject();
        }
        JsonElement parsed = JsonParser.parseString(body); // JsonSyntaxException on malformed -> caller returns false
        if (!parsed.isJsonObject()) {
            throw new IOException("owner override root is not a JSON object: " + file);
        }
        return parsed.getAsJsonObject();
    }

    @Nonnull
    private static JsonArray arrayOf(@Nonnull JsonObject root, @Nonnull String arrayKey) {
        if (root.has(arrayKey) && root.get(arrayKey).isJsonArray()) {
            return root.getAsJsonArray(arrayKey);
        }
        return new JsonArray();
    }

    private static int indexOfMatch(@Nonnull JsonArray arr, @Nonnull String matchField, @Nonnull String matchValue) {
        String want = matchValue.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < arr.size(); i++) {
            JsonElement el = arr.get(i);
            if (!el.isJsonObject()) {
                continue;
            }
            JsonElement v = el.getAsJsonObject().get(matchField);
            if (v != null && v.isJsonPrimitive()
                    && v.getAsString().trim().toLowerCase(Locale.ROOT).equals(want)) {
                return i;
            }
        }
        return -1;
    }

    /** Walk/create intermediate objects for a dotted PascalCase path and set the leaf primitive. */
    private static void setInto(@Nonnull JsonObject target, @Nonnull String dottedPath,
            @Nonnull JsonPrimitive value) {
        String[] parts = dottedPath.split("\\.");
        JsonObject cursor = target;
        for (int i = 0; i < parts.length - 1; i++) {
            JsonElement next = cursor.get(parts[i]);
            if (next != null && next.isJsonObject()) {
                cursor = next.getAsJsonObject();
            } else {
                JsonObject created = new JsonObject();
                cursor.add(parts[i], created);
                cursor = created;
            }
        }
        cursor.add(parts[parts.length - 1], value);
    }

    /** Navigate to the parent of a dotted path and remove the leaf; no-op if the chain is absent. */
    private static void removeInto(@Nonnull JsonObject root, @Nonnull String dottedPath) {
        String[] parts = dottedPath.split("\\.");
        JsonObject cursor = root;
        for (int i = 0; i < parts.length - 1; i++) {
            JsonElement next = cursor.get(parts[i]);
            if (next == null || !next.isJsonObject()) {
                return;
            }
            cursor = next.getAsJsonObject();
        }
        cursor.remove(parts[parts.length - 1]);
    }

    /** Box a scalar into a Gson primitive; a Number keeps its int/double identity in the emitted JSON. */
    @Nonnull
    private static JsonPrimitive toPrimitive(@Nonnull Object value) {
        if (value instanceof String s) {
            return new JsonPrimitive(s);
        }
        if (value instanceof Boolean b) {
            return new JsonPrimitive(b);
        }
        if (value instanceof Number n) {
            return new JsonPrimitive(n);
        }
        return new JsonPrimitive(String.valueOf(value)); // defensive; callers pass String/Boolean/Number
    }

    /** Atomic write (temp sibling + move) so a crash mid-write cannot corrupt the owner file. */
    private static boolean write(@Nonnull Path file, @Nonnull JsonObject root) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String json = GSON.toJson(root) + System.lineSeparator();
            Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception ex) {
            warn("write failed for " + file + ": " + ex.getMessage());
            return false;
        }
    }

    private static void warn(@Nonnull String message) {
        if (LOGGER == null) {
            return;
        }
        try {
            LOGGER.atWarning().log("[JsonOverrideWriter] " + message);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM
        }
    }
}
