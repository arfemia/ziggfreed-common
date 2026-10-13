package com.ziggfreed.common.util;

import java.nio.file.Path;
import java.nio.file.Paths;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * The rules EVERY {@code mods/ziggfreedcommon/*.json} owner file shares, in one place so the six
 * readers cannot drift apart: which top-level keys are entries and which are reserved, and how a
 * file says which schema it speaks.
 *
 * <p><b>{@code $}-prefixed keys are RESERVED, uniformly.</b> A top-level key starting with {@code $}
 * is never an entry id: {@code $Comment} is documentation and {@code $SchemaVersion} is the file's
 * schema marker, and the namespace stays open so a later marker needs no shape-sniffing. Every
 * reader asks {@link #isReservedKey} rather than spelling its own test.
 *
 * <p><b>{@code $SchemaVersion} names the shape of the FILE, and absent means {@value
 * #SCHEMA_VERSION}.</b> Today there is exactly one shape per file, so authoring the marker changes
 * nothing; it exists so that a future structural change has something to branch on instead of
 * guessing from the shape. A file declaring a NEWER version than this library reads is refused
 * whole - one warning naming the file and both numbers, nothing in it in force - because silently
 * reading a future shape as today's is exactly the misread the marker exists to prevent.
 */
public final class OwnerFiles {

    /**
     * The folder every owner file sits in, {@code mods/ziggfreedcommon} under the server's working
     * directory: each reader's {@code DEFAULT_DIRECTORY} points here.
     */
    public static final Path DIRECTORY = Paths.get("mods", "ziggfreedcommon");

    /** The reserved top-level key naming the schema the file speaks. */
    public static final String SCHEMA_VERSION_KEY = "$SchemaVersion";

    /** The one schema this library reads today; also what an absent marker means. */
    public static final int SCHEMA_VERSION = 1;

    private OwnerFiles() {
    }

    /**
     * True when {@code key} can never be an entry id: null, blank, or {@code $}-prefixed
     * (documentation and file-level markers).
     */
    public static boolean isReservedKey(@Nullable String key) {
        return key == null || key.isBlank() || key.startsWith("$");
    }

    /**
     * The schema version {@code root} declares, or {@value #SCHEMA_VERSION} when it declares none
     * (every file written before the marker existed is a version-1 file). A marker that is not a
     * number is ignored rather than guessed at.
     */
    public static int declaredSchemaVersion(@Nonnull JsonObject root) {
        JsonElement declared = root.get(SCHEMA_VERSION_KEY);
        if (declared == null || !declared.isJsonPrimitive()) {
            return SCHEMA_VERSION;
        }
        JsonPrimitive primitive = declared.getAsJsonPrimitive();
        if (!primitive.isNumber()) {
            return SCHEMA_VERSION;
        }
        return primitive.getAsInt();
    }

    /**
     * Can this library read {@code root} at all? False when the file declares a schema newer than
     * {@value #SCHEMA_VERSION}, with ONE warning through {@link SafeLog} naming {@code file} and
     * both numbers; the caller then treats the file as empty. The wording is shared so all six
     * owner files refuse the same way.
     *
     * @param logTag the reader's own log prefix, e.g. {@code "commerce"} or {@code "dialogue"}
     * @param file   what to call the file in the warning (a {@code Path} reads fine)
     */
    public static boolean schemaReadable(@Nonnull JsonObject root, @Nonnull String logTag,
            @Nonnull Object file) {
        int declared = declaredSchemaVersion(root);
        if (declared <= SCHEMA_VERSION) {
            return true;
        }
        SafeLog.warn("[" + logTag + "] " + file + " declares " + SCHEMA_VERSION_KEY + " " + declared
                + ", newer than the " + SCHEMA_VERSION + " this library reads, so nothing in it is in "
                + "force; update the library or rewrite the file in the older shape");
        return false;
    }

    /**
     * A file-level on/off switch such as {@code $Enabled}: on unless {@code root} says false in so many
     * words. A missing or newer-schema file ({@link #schemaReadable}) and a missing key read as on; a value
     * that is not true or false reads as on with one WARNING,
     * {@code [<logTag>] <file>: <key> <notBoolean>}; false logs {@code [<logTag>] <file> <offLine>} at
     * INFO, or nothing when {@code offLine} is null. Each reader passes its own wording.
     *
     * @param notBoolean what the warning says after the key, e.g. {@code "must be true or false, so the
     *                   Almanac stays on"}
     * @param offLine    what the INFO line says after the file when the switch is off, or null for none
     */
    public static boolean flag(@Nullable JsonObject root, @Nonnull String key, @Nonnull Object file,
            @Nonnull String logTag, @Nonnull String notBoolean, @Nullable String offLine) {
        if (root == null || !schemaReadable(root, logTag, file)) {
            return true;
        }
        JsonElement value = root.get(key);
        if (value == null) {
            return true;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            SafeLog.warn("[" + logTag + "] " + file + ": " + key + " " + notBoolean);
            return true;
        }
        boolean on = value.getAsBoolean();
        if (!on && offLine != null) {
            SafeLog.info("[" + logTag + "] " + file + " " + offLine);
        }
        return on;
    }
}
