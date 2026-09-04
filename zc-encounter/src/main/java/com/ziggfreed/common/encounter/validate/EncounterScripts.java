package com.ziggfreed.common.encounter.validate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.assetstore.map.AssetMapWithIndexes;
import com.hypixel.hytale.builtin.encountermanager.EncounterManager;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.BuilderInfo;
import com.hypixel.hytale.server.npc.asset.builder.BuilderManager;
import com.hypixel.hytale.server.spawning.assets.spawnmarker.config.SpawnMarker;
import com.ziggfreed.common.encounter.event.Encounters;
import com.ziggfreed.common.encounter.signal.EncounterSignal;
import com.ziggfreed.common.util.SafeLog;

/**
 * The loaded encounter scripts as authored: every builder the engine's manager holds under the
 * encounter category, its file read back and scanned ({@link EncounterScriptScan}), with any
 * builder it references resolved by name through the same manager.
 *
 * <p>Whether a script authors a given beat is asked on the tick (does this script announce its own
 * engage?), so the answer is cached per script id and dropped on a builder reload.
 */
public final class EncounterScripts {

    private static final Map<String, Boolean> AUTHORS_ENGAGED = new ConcurrentHashMap<>();

    private EncounterScripts() {
    }

    /** Every loaded encounter script, scanned, keyed by id; empty when the engine is not up. */
    @Nonnull
    public static Map<String, EncounterScriptScan> scanLoaded() {
        Map<String, EncounterScriptScan> out = new LinkedHashMap<>();
        NPCPlugin npc = NPCPlugin.get();
        if (npc == null) {
            return out;
        }
        BuilderManager manager = npc.getBuilderManager();
        Map<String, JsonObject> files = new HashMap<>();
        for (BuilderInfo info : manager.getAllBuilders().values()) {
            if (info == null || info.getBuilder() == null || info.getBuilder().category() != EncounterManager.class) {
                continue;
            }
            JsonObject root = fileOf(info, files);
            if (root == null) {
                continue;
            }
            out.put(info.getKeyName(), EncounterScriptScan.scan(info.getKeyName(), info.getBuilder().isSpawnable(),
                    root, name -> referenced(npc, manager, name, files)));
        }
        return out;
    }

    /** The scan of ONE script by id, or null when it is not loaded or cannot be read. */
    @Nullable
    public static EncounterScriptScan scan(@Nonnull String encounterId) {
        NPCPlugin npc = NPCPlugin.get();
        if (npc == null) {
            return null;
        }
        BuilderManager manager = npc.getBuilderManager();
        int index = npc.getIndex(encounterId);
        BuilderInfo info = index == AssetMapWithIndexes.NOT_FOUND ? null : manager.tryGetBuilderInfo(index);
        if (info == null || info.getBuilder().category() != EncounterManager.class) {
            return null;
        }
        Map<String, JsonObject> files = new HashMap<>();
        JsonObject root = fileOf(info, files);
        return root == null ? null : EncounterScriptScan.scan(info.getKeyName(), info.getBuilder().isSpawnable(),
                root, name -> referenced(npc, manager, name, files));
    }

    /**
     * Whether {@code encounterId}'s script authors its own {@code zc:engaged} beat. Cached; a script
     * that cannot be read answers true, so the library never engages on its own for a script it
     * cannot see.
     */
    public static boolean authorsEngaged(@Nonnull String encounterId) {
        return AUTHORS_ENGAGED.computeIfAbsent(encounterId.toLowerCase(Locale.ROOT), k -> {
            EncounterScriptScan scan = scan(encounterId);
            return scan == null || scan.authors(EncounterSignal.Moment.ENGAGED);
        });
    }

    /** Drop every cached answer; called on a builder reload. */
    public static void invalidate() {
        AUTHORS_ENGAGED.clear();
    }

    /**
     * Whether an id resolves to a loaded NPC ROLE (a builder of the role category, which an
     * encounter script of the same name is not), or null when the engine is not up to ask.
     */
    @Nullable
    public static Predicate<String> roleExists() {
        NPCPlugin npc = NPCPlugin.get();
        if (npc == null) {
            return null;
        }
        return id -> {
            try {
                return npc.hasRoleName(id);
            } catch (Throwable t) {
                return false;
            }
        };
    }

    /**
     * Every role the loaded spawn markers name, keyed by marker id: what {@code TriggerSpawners}
     * and a worldgen marker would raise, and the first place a role id is named that the engine
     * refuses only at spawn time. Empty when the engine is not up.
     */
    @Nonnull
    public static Map<String, List<String>> spawnMarkerRoles() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        try {
            for (SpawnMarker marker : SpawnMarker.getAssetMap().getAssetMap().values()) {
                if (marker == null || marker.getId() == null || marker.getWeightedConfigurations() == null) {
                    continue;
                }
                List<String> roles = new ArrayList<>();
                marker.getWeightedConfigurations().forEach(configuration -> {
                    if (configuration != null && configuration.getNpc() != null && !configuration.getNpc().isBlank()) {
                        roles.add(configuration.getNpc());
                    }
                });
                if (!roles.isEmpty()) {
                    out.put(marker.getId(), roles);
                }
            }
        } catch (Throwable t) {
            SafeLog.fine(Encounters.LOG_PREFIX + " the spawn markers could not be read: " + t.getMessage());
        }
        return out;
    }

    @Nullable
    private static JsonObject referenced(@Nonnull NPCPlugin npc, @Nonnull BuilderManager manager, @Nonnull String name,
            @Nonnull Map<String, JsonObject> files) {
        int index = npc.getIndex(name);
        if (index == AssetMapWithIndexes.NOT_FOUND) {
            return null;
        }
        BuilderInfo info = manager.tryGetBuilderInfo(index);
        return info == null ? null : fileOf(info, files);
    }

    @Nullable
    private static JsonObject fileOf(@Nonnull BuilderInfo info, @Nonnull Map<String, JsonObject> files) {
        String key = info.getKeyName();
        if (files.containsKey(key)) {
            return files.get(key);
        }
        JsonObject root = read(info.getPath());
        files.put(key, root);
        return root;
    }

    @Nullable
    private static JsonObject read(@Nullable Path path) {
        if (path == null) {
            return null;
        }
        try {
            String body = Files.readString(path, StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(body);
            return root != null && root.isJsonObject() ? root.getAsJsonObject() : null;
        } catch (Exception e) {
            SafeLog.fine(Encounters.LOG_PREFIX + " could not read the script at " + path + ": " + e.getMessage());
            return null;
        }
    }
}
