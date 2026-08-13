package com.ziggfreed.common.world;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ziggfreed.common.CommonLog;

/**
 * The server owner's world-selector layer, at {@code mods/ziggfreedcommon/world-selectors.json}.
 *
 * <p>It exists for the one thing a server owner cannot express any other way: <b>their main world
 * is not called what the shipped files assume.</b> A fresh Hytale world is named {@code default}
 * and the shipped selector matches that name, but a server may run any world as its main one - so
 * re-pointing the selector has to be possible without editing a jar or a content pack an update
 * would overwrite. Redeclaring the shipped file's id here does exactly that, and the SAME move
 * works for any selector any pack ships.
 *
 * <pre>{@code
 * {
 *   "Zc_Default":   { "Names": ["default"], "Match": ["my_server_world"] },
 *   "My_Arenas":    { "Names": ["arena"],   "Match": ["*_arena*"] },
 *   "My_Outdoors":  { "Names": ["outdoor"], "Match": ["*"], "ExcludeNames": ["arena"] }
 * }
 * }</pre>
 *
 * <p><b>A key is an asset id and a value is a whole selector file.</b> The four keys are the ones
 * a {@code Server/ZiggfreedCommon/WorldSelectors/<id>.json} carries, and an entry REPLACES the
 * shipped file of that id outright rather than merging into it - so what a name means is readable
 * from one place instead of assembled from two. Adding a name is a new key of your own; widening
 * an existing one is a second file handing out the same name, which is always allowed.
 *
 * <p>The file is optional. A missing one leaves every shipped selector standing, and a malformed
 * one warns, clears the layer and is NEVER rewritten, so a hand-edit typo costs the overrides
 * rather than the file. Keys beginning with {@code $} are documentation and are skipped, so a
 * {@code $Comment} survives beside real entries.
 */
public final class WorldSelectorOverrides {

    private static final WorldSelectorOverrides INSTANCE = new WorldSelectorOverrides();

    @Nonnull
    public static WorldSelectorOverrides getInstance() {
        return INSTANCE;
    }

    @Nonnull
    private volatile Path file = Paths.get("mods", "ziggfreedcommon", "world-selectors.json");

    private WorldSelectorOverrides() {
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

    /**
     * (Re)read the file and publish it as {@link WorldSelectorConfig}'s owner layer. Safe to call
     * any time: a missing or malformed file publishes an EMPTY layer, which restores the shipped
     * and pack selectors rather than leaving a half-applied one standing.
     */
    public void load() {
        WorldSelectorConfig.getInstance().mergeOwnerLayer(read());
    }

    /** The parsed layer, {@code id -> def}. Public so a consumer command can preview a reload. */
    @Nonnull
    public Map<String, WorldSelectorDef> read() {
        Path path = file;
        try {
            if (!Files.exists(path)) {
                return Map.of();
            }
            String body = Files.readString(path, StandardCharsets.UTF_8);
            if (body.isBlank()) {
                return Map.of();
            }
            JsonElement root = JsonParser.parseString(body);
            if (root == null || !root.isJsonObject()) {
                warn(path + " is not a JSON object - world-selector overrides ignored");
                return Map.of();
            }
            return parse(root.getAsJsonObject());
        } catch (Exception e) {
            warn("could not read " + path + ": " + e.getMessage());
            return Map.of();
        }
    }

    /** The PURE parser: one JSON object of {@code id -> selector body} into defs. */
    @Nonnull
    public static Map<String, WorldSelectorDef> parse(@Nonnull JsonObject root) {
        Map<String, WorldSelectorDef> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank() || key.startsWith("$")) {
                continue; // $Comment and friends are documentation, not entries
            }
            JsonElement value = entry.getValue();
            if (value == null || !value.isJsonObject()) {
                continue;
            }
            JsonObject body = value.getAsJsonObject();
            String id = key.trim().toLowerCase(Locale.ROOT);
            out.put(id, new WorldSelectorDef(id,
                    strings(body, "Names"),
                    strings(body, "Match"),
                    strings(body, "GameplayConfig"),
                    strings(body, "ExcludeNames")));
        }
        return out;
    }

    /**
     * One authored string list. A bare string is accepted as a one-entry list, because
     * {@code "Match": "my_world"} is what an owner writes first and refusing it would cost them a
     * selector with no visible reason.
     */
    @Nullable
    private static String[] strings(@Nonnull JsonObject body, @Nonnull String key) {
        JsonElement element = body.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            return new String[]{element.getAsString()};
        }
        if (!element.isJsonArray()) {
            return null;
        }
        JsonArray array = element.getAsJsonArray();
        String[] out = new String[array.size()];
        for (int i = 0; i < array.size(); i++) {
            JsonElement item = array.get(i);
            out[i] = item != null && item.isJsonPrimitive() ? item.getAsString() : "";
        }
        return out;
    }

    private static void warn(@Nonnull String message) {
        try {
            CommonLog.LOGGER.atWarning().log("[worldselector] " + message);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM: the flogger LOGGER can throw; swallow it.
        }
    }
}
