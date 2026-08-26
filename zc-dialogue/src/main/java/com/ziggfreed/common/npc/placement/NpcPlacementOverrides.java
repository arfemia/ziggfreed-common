package com.ziggfreed.common.npc.placement;

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
import com.ziggfreed.common.util.JsonOverrideWriter;
import com.ziggfreed.common.util.OwnerFiles;
import com.ziggfreed.common.util.SafeLog;

/**
 * The server owner's switch over placements, at {@code mods/ziggfreedcommon/npc-placements.json}.
 *
 * <p>It exists because an admin needs to stop an NPC a content pack ships WITHOUT editing that
 * pack (which an update would overwrite) and without waiting for a restart. It feeds the gate
 * chain, so a stop despawns whatever is already standing on the next sweep.
 *
 * <pre>{@code
 * {
 *   "mmo_hub":   { "enabled": false },   // one placement, by id
 *   "yourmod_*": { "enabled": false },   // every placement whose id starts with "yourmod_"
 *   "*":         { "enabled": false }    // everything, the global stop
 * }
 * }</pre>
 *
 * <p><b>One key grammar, no sections.</b> A key is a placement id, a trailing-{@code *} prefix, or
 * the bare {@code *}. The prefix form is how a whole mod's placements are addressed at once (ship
 * your placements with a shared filename prefix and one line covers them), so there is no second,
 * nested "namespace section" shape to learn or to disambiguate from a plain entry.
 *
 * <p><b>The most specific key wins</b>, in the order exact id, longest matching prefix, bare
 * {@code *}. So a global stop plus a single re-enable does what it reads like:
 * {@code {"*": {"enabled": false}, "mmo_hub": {"enabled": true}}} leaves exactly the hub standing.
 * A placement no key mentions is enabled.
 *
 * <p>Reads are lock-free off an immutable snapshot; a write rewrites one leaf through
 * {@link JsonOverrideWriter} (atomic, sibling keys and any {@code $Comment} preserved) and then
 * reloads. A malformed file is never overwritten and is treated as empty, so a hand-edit typo
 * costs the overrides, not the file.
 *
 * <p><b>{@code $}-prefixed top-level keys are reserved, never entries</b> ({@link OwnerFiles}):
 * {@code $Comment} is documentation, and {@code $SchemaVersion} names the file's schema (absent
 * means 1, the shape above; a newer number than this library reads refuses the whole file with
 * one warning).
 */
public final class NpcPlacementOverrides {

    /** The bare wildcard key: the global stop. */
    public static final String ALL = "*";

    /** The leaf every entry carries. */
    private static final String ENABLED = "enabled";

    private static final NpcPlacementOverrides INSTANCE = new NpcPlacementOverrides();

    @Nonnull
    public static NpcPlacementOverrides getInstance() {
        return INSTANCE;
    }

    @Nonnull
    private volatile Path file = Paths.get("mods", "ziggfreedcommon", "npc-placements.json");

    /** Immutable snapshot: match key (lower-cased) to its authored enabled flag. */
    @Nonnull
    private volatile Map<String, Boolean> entries = Map.of();

    private NpcPlacementOverrides() {
    }

    /** Point the overrides at a different file (tests, or a consumer with its own data dir). */
    public void setFile(@Nonnull Path path) {
        this.file = path;
        load();
    }

    @Nonnull
    public Path getFile() {
        return file;
    }

    // ==================== read ====================

    /**
     * (Re)read the file. Safe to call any time; a missing file clears the overrides, a malformed
     * one warns and clears them (it is never rewritten).
     */
    public void load() {
        Map<String, Boolean> parsed = new LinkedHashMap<>();
        Path path = file;
        try {
            if (Files.exists(path)) {
                String body = Files.readString(path, StandardCharsets.UTF_8);
                if (!body.isBlank()) {
                    JsonElement root = JsonParser.parseString(body);
                    if (root != null && root.isJsonObject()) {
                        if (OwnerFiles.schemaReadable(root.getAsJsonObject(), "placement", path)) {
                            parse(root.getAsJsonObject(), parsed);
                        }
                    } else {
                        SafeLog.warn("[placement] " + path + " is not a JSON object - overrides ignored");
                    }
                }
            }
        } catch (Exception e) {
            SafeLog.warn("[placement] could not read " + path + ": " + e.getMessage());
            parsed.clear();
        }
        entries = Collections.unmodifiableMap(parsed);
    }

    private static void parse(@Nonnull JsonObject root, @Nonnull Map<String, Boolean> out) {
        for (Map.Entry<String, JsonElement> e : root.entrySet()) {
            String key = e.getKey();
            if (OwnerFiles.isReservedKey(key)) {
                continue; // $Comment, $SchemaVersion and friends are file-level, not entries
            }
            JsonElement value = e.getValue();
            if (value == null || !value.isJsonObject()) {
                continue;
            }
            JsonElement enabled = value.getAsJsonObject().get(ENABLED);
            if (enabled != null && enabled.isJsonPrimitive() && enabled.getAsJsonPrimitive().isBoolean()) {
                out.put(key.trim().toLowerCase(Locale.ROOT), enabled.getAsBoolean());
            }
        }
    }

    /** Is {@code placementId} allowed by the owner file? Absent from every key means yes. */
    public boolean isEnabled(@Nullable String placementId) {
        Boolean resolved = resolve(placementId);
        return resolved == null || resolved;
    }

    /**
     * The PURE resolution: the authored flag for {@code placementId} under the most specific
     * matching key, or {@code null} when no key mentions it. Exact id beats the longest matching
     * prefix, which beats the bare wildcard.
     */
    @Nullable
    public Boolean resolve(@Nullable String placementId) {
        return resolve(entries, placementId);
    }

    /** The pure resolver over an explicit entry map (the unit-testable core). */
    @Nullable
    public static Boolean resolve(@Nonnull Map<String, Boolean> entries, @Nullable String placementId) {
        if (placementId == null || placementId.isBlank() || entries.isEmpty()) {
            return null;
        }
        String id = placementId.trim().toLowerCase(Locale.ROOT);

        Boolean exact = entries.get(id);
        if (exact != null) {
            return exact;
        }

        Boolean bestPrefix = null;
        int bestPrefixLength = -1;
        for (Map.Entry<String, Boolean> e : entries.entrySet()) {
            String key = e.getKey();
            if (key.length() < 2 || !key.endsWith(ALL)) {
                continue;
            }
            String prefix = key.substring(0, key.length() - 1);
            if (id.startsWith(prefix) && prefix.length() > bestPrefixLength) {
                bestPrefixLength = prefix.length();
                bestPrefix = e.getValue();
            }
        }
        if (bestPrefix != null) {
            return bestPrefix;
        }

        return entries.get(ALL);
    }

    /** The current snapshot (diagnostics, a listing command). */
    @Nonnull
    public Map<String, Boolean> all() {
        return entries;
    }

    // ==================== write ====================

    /**
     * Write {@code enabled} for {@code key} (a placement id, a {@code prefix*}, or {@code "*"})
     * and reload. Returns false on any IO failure, in which case the file is left intact and the
     * in-memory snapshot is unchanged.
     *
     * <p>The caller is expected to force a reconcile sweep afterwards, which is what makes a stop
     * take effect immediately rather than at the next restart.
     */
    public boolean setEnabled(@Nonnull String key, boolean enabled) {
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return false;
        }
        boolean ok = JsonOverrideWriter.setLeaf(file, normalized + "." + ENABLED, enabled);
        if (ok) {
            load();
        }
        return ok;
    }

    /**
     * Remove {@code key}'s entry entirely, so the placement falls back to whatever the content
     * itself says. Returns false on any IO failure.
     */
    public boolean clear(@Nonnull String key) {
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return false;
        }
        boolean ok = JsonOverrideWriter.setLeaf(file, normalized, null);
        if (ok) {
            load();
        }
        return ok;
    }
}
