package com.ziggfreed.common.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Takes the settings a release no longer reads out of a server owner's config files, so a file never
 * says one thing while the server does another.
 *
 * <p>A mod keeps ONE list of its retired settings ({@link Entry}: the owner file, the setting's dotted
 * path in it, and one sentence on where it went) and runs it over its own owner folder once at setup,
 * before anything reads those files: CommonLib's list is {@link RetiredLibrarySettings}, over
 * {@code mods/ziggfreedcommon/}; a consumer keeps its own list for its own folder and never lists a
 * CommonLib file. A card that retires a setting adds its line to that list and never touches this pass.
 *
 * <h2>What one pass does</h2>
 *
 * <p>For each listed file that still writes one of its retired settings: a copy of the file exactly as
 * it was goes beside it ({@code <file>.pre-<release>}, or {@code .pre-<release>.2}, {@code .3}, ... when
 * an earlier copy took the name, so no copy is ever overwritten), the settings come out, the file is
 * written back in one step ({@link JsonOverrideWriter}'s atomic write), and the caller logs the one
 * {@link Outcome#line()} per file through its own logger. Every other key, its order, its value and its
 * number spelling stay as they were; only comments, which JSON has no place for, live on in the copy.
 *
 * <h2>When it acts</h2>
 *
 * <p>What the pass acts on is the setting itself, never a version: once a setting is out of the file,
 * the next start finds nothing and writes and logs nothing, and a setting written back in by hand comes
 * out again on the next start, with a fresh copy.
 * That holds only while no save path writes a retired setting back, so retiring one also takes it out of
 * the config's own data class. A file with nothing listed, a missing file, a file that is not a JSON
 * object or does not parse (its own reader reports that) and a file declaring a newer
 * {@code $SchemaVersion} than this library reads ({@link OwnerFiles}) are all left exactly as they are.
 * The pass never throws: a file it could not copy or write is left as it was, and its outcome says why.
 *
 * <h2>Paths</h2>
 *
 * <p>A path is the keys from the file's top level down to the setting, joined by dots
 * ({@code clamps.HitDamage}). A {@code *} segment stands for every entry of an object (its
 * {@code $}-prefixed keys aside, which are never entries) or every element of an array, so
 * {@code overrides.*.pools.*.delay} reaches a key inside every pool of every override. The last segment
 * names the key that comes out, matched whatever its case, since a setting written in another case was
 * read by nothing either.
 */
public final class RetiredSettings {

    /** The segment that stands for every entry of an object or every element of an array. */
    public static final String EVERY = "*";

    private static final String COPY_INFIX = ".pre-";

    private RetiredSettings() {
    }

    /**
     * One retired setting.
     *
     * @param file the owner file, relative to the owner folder the pass runs over ({@code combat-text.json})
     * @param path the setting's dotted path in that file (see the class doc); its last segment is a key
     * @param note one plain sentence for the server owner: why it went, or where the setting lives now
     */
    public record Entry(@Nonnull String file, @Nonnull String path, @Nonnull String note) {

        public Entry {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(note, "note");
            if (file.isBlank() || path.isBlank()) {
                throw new IllegalArgumentException("a retired setting names its file and its path");
            }
            String[] segments = path.split("\\.", -1);
            for (String segment : segments) {
                if (segment.isBlank()) {
                    throw new IllegalArgumentException("retired setting path '" + path + "' has an empty segment");
                }
            }
            if (EVERY.equals(segments[segments.length - 1])) {
                throw new IllegalArgumentException("retired setting path '" + path
                        + "' must end in the key that comes out, not " + EVERY);
            }
        }

        /** The key that comes out, the path's last segment. */
        @Nonnull
        public String key() {
            return path.substring(path.lastIndexOf('.') + 1);
        }
    }

    /**
     * What one pass did to one file that still wrote a retired setting.
     *
     * @param file    the file, as the owner folder resolves it
     * @param removed each retired path that came out, in list order, with how many times when more than
     *                once (a {@code *} path can match many)
     * @param notes   the distinct notes of those settings, in list order
     * @param copy    the copy of the file as it was, or null when none was kept
     * @param failure why the file was left as it was, or null when the settings came out
     */
    public record Outcome(@Nonnull Path file, @Nonnull List<String> removed, @Nonnull List<String> notes,
            @Nullable Path copy, @Nullable String failure) {

        public Outcome {
            removed = List.copyOf(removed);
            notes = List.copyOf(notes);
        }

        /** True when the file still writes the settings: it could not be copied or written. */
        public boolean failed() {
            return failure != null;
        }

        /** The one plain-English log line for this file: an INFO line, or a WARNING one when {@link #failed()}. */
        @Nonnull
        public String line() {
            String names = String.join(", ", removed);
            StringBuilder sb = new StringBuilder();
            if (failed()) {
                sb.append(file).append(" still writes ").append(names)
                        .append(", which this version no longer reads, but they could not be taken out (")
                        .append(failure).append("), so the file was left as it was.");
            } else {
                sb.append(file).append(": removed ").append(names)
                        .append(", which this version no longer reads; the file as it was is kept beside it as ")
                        .append(copy == null ? "a copy" : copy.getFileName()).append('.');
            }
            for (String note : notes) {
                if (!note.isBlank()) {
                    sb.append(' ').append(note);
                }
            }
            return sb.toString();
        }
    }

    /**
     * Run the pass over {@code ownerDir}: every listed file that still writes one of its retired settings
     * loses them, a copy of it kept beside it. Never throws.
     *
     * @param release what the copies are named for ({@code combat-text.json.pre-2.0.0}): the release that
     *                retired the settings
     * @return one outcome per file the pass found a retired setting in, in list order; empty when it
     *         found none, which is every start after the first
     */
    @Nonnull
    public static List<Outcome> strip(@Nonnull Path ownerDir, @Nonnull String release,
            @Nonnull List<Entry> entries) {
        Map<String, List<Entry>> byFile = new LinkedHashMap<>();
        for (Entry entry : entries) {
            byFile.computeIfAbsent(entry.file(), f -> new ArrayList<>()).add(entry);
        }
        List<Outcome> outcomes = new ArrayList<>();
        for (Map.Entry<String, List<Entry>> file : byFile.entrySet()) {
            Outcome outcome = stripFile(ownerDir.resolve(file.getKey()), release, file.getValue());
            if (outcome != null) {
                outcomes.add(outcome);
            }
        }
        return outcomes;
    }

    /** One file: null when it is missing, unreadable, newer than this library, or writes nothing listed. */
    @Nullable
    private static Outcome stripFile(@Nonnull Path file, @Nonnull String release, @Nonnull List<Entry> entries) {
        JsonObject root;
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (parsed == null || !parsed.isJsonObject()) {
                return null;
            }
            root = parsed.getAsJsonObject();
        } catch (Exception unreadable) {
            return null; // the file's own reader names a file it cannot read; this pass only stays out of it
        }
        if (OwnerFiles.declaredSchemaVersion(root) > OwnerFiles.SCHEMA_VERSION) {
            return null;
        }

        List<String> removed = new ArrayList<>();
        Set<String> notes = new LinkedHashSet<>();
        for (Entry entry : entries) {
            int count = remove(root, entry.path().split("\\."), 0);
            if (count > 0) {
                removed.add(count == 1 ? entry.path() : entry.path() + " (" + count + ")");
                notes.add(entry.note());
            }
        }
        if (removed.isEmpty()) {
            return null;
        }
        List<String> noteList = new ArrayList<>(notes);

        Path copy;
        try {
            copy = firstFree(file.resolveSibling(file.getFileName() + COPY_INFIX + release));
            Files.copy(file, copy, StandardCopyOption.COPY_ATTRIBUTES);
        } catch (Exception e) {
            return new Outcome(file, removed, noteList, null, "no copy of it could be kept: " + e.getMessage());
        }
        if (!JsonOverrideWriter.write(file, root)) {
            try {
                Files.deleteIfExists(copy);
            } catch (IOException ignored) {
                // the copy is the file as it still is: harmless to leave
            }
            return new Outcome(file, removed, noteList, null, "it could not be written");
        }
        return new Outcome(file, removed, noteList, copy, null);
    }

    /**
     * Take every match of {@code segments} (from {@code at} down) out of {@code node}.
     *
     * @return how many keys came out
     */
    private static int remove(@Nullable JsonElement node, @Nonnull String[] segments, int at) {
        if (node == null) {
            return 0;
        }
        String segment = segments[at];
        if (EVERY.equals(segment)) {
            int count = 0;
            if (node.isJsonObject()) {
                for (Map.Entry<String, JsonElement> child : node.getAsJsonObject().entrySet()) {
                    if (!OwnerFiles.isReservedKey(child.getKey())) {
                        count += remove(child.getValue(), segments, at + 1);
                    }
                }
            } else if (node.isJsonArray()) {
                for (JsonElement element : node.getAsJsonArray()) {
                    count += remove(element, segments, at + 1);
                }
            }
            return count;
        }
        if (!node.isJsonObject()) {
            return 0;
        }
        JsonObject object = node.getAsJsonObject();
        List<String> matching = new ArrayList<>();
        for (String key : object.keySet()) {
            if (key.equalsIgnoreCase(segment)) {
                matching.add(key);
            }
        }
        boolean last = at == segments.length - 1;
        int count = 0;
        for (String key : matching) {
            if (last) {
                object.remove(key);
                count++;
            } else {
                count += remove(object.get(key), segments, at + 1);
            }
        }
        return count;
    }

    /** {@code first}, or {@code first.2}, {@code first.3}, ... the first that does not exist yet. */
    @Nonnull
    private static Path firstFree(@Nonnull Path first) {
        Path candidate = first;
        for (int n = 2; Files.exists(candidate); n++) {
            candidate = first.resolveSibling(first.getFileName() + "." + n);
        }
        return candidate;
    }
}
