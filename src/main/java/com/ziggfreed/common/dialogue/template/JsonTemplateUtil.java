package com.ziggfreed.common.dialogue.template;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.annotation.Nonnull;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * Shared, pure JSON primitives for dialogue template resolution: deep-clone,
 * {@code {{param}}} substitution (with the "empty resolve drops the holding key"
 * rule that lets templates expose optional fields), recursive object merge, and
 * the string-keyed {@code Nodes} map merge/append with the per-index
 * {@code Options} overlay semantics. The dialogue-scoped copy that lives in
 * {@code ziggfreed-common} (an MMO sibling keeps its own copy for its non-dialogue
 * resolvers); only the engine-jar + Gson are dependencies.
 */
public final class JsonTemplateUtil {

    private JsonTemplateUtil() {
    }

    /**
     * How a {@code nodeOverrides} node merges its {@code Options} array against the
     * template node. {@link #MERGE_BY_INDEX} overlays partial option {@code i} onto
     * template option {@code i} (option order is load-bearing - the by-convention
     * loc key is {@code dialogue.<id>.<node>.opt.<INDEX>}); {@link #REPLACE} swaps
     * the {@code Options} array wholesale (an {@code Options!} key).
     */
    public enum OptionMode { MERGE_BY_INDEX, REPLACE }

    /** Deep-clone {@code source} via a JSON round-trip (Gson exposes no public deep-copy). */
    @Nonnull
    public static JsonObject deepClone(@Nonnull JsonObject source) {
        return JsonParser.parseString(source.toString()).getAsJsonObject();
    }

    /**
     * Walk {@code node} recursively, replacing {@code {{key}}} substrings in string
     * values with {@code params.get(key)}. Mutates in place. A string whose tokens
     * all resolved to empty drops its holding key (templates expose optional fields
     * a caller omits via an empty param).
     */
    public static void substituteParamsInPlace(@Nonnull JsonElement node, @Nonnull JsonObject params) {
        if (node.isJsonObject()) {
            JsonObject obj = node.getAsJsonObject();
            List<String> keysToDrop = new ArrayList<>();
            List<Map.Entry<String, JsonElement>> entries = new ArrayList<>(obj.entrySet());
            for (Map.Entry<String, JsonElement> entry : entries) {
                JsonElement value = entry.getValue();
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                    String original = value.getAsString();
                    String substituted = substituteString(original, params);
                    if (substituted.isEmpty() && !original.isEmpty()) {
                        keysToDrop.add(entry.getKey());
                    } else if (!substituted.equals(original)) {
                        obj.addProperty(entry.getKey(), substituted);
                    }
                } else if (value.isJsonObject() || value.isJsonArray()) {
                    substituteParamsInPlace(value, params);
                }
            }
            for (String k : keysToDrop) obj.remove(k);
        } else if (node.isJsonArray()) {
            JsonArray arr = node.getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                JsonElement elem = arr.get(i);
                if (elem.isJsonPrimitive() && elem.getAsJsonPrimitive().isString()) {
                    String substituted = substituteString(elem.getAsString(), params);
                    arr.set(i, new JsonPrimitive(substituted));
                } else if (elem.isJsonObject() || elem.isJsonArray()) {
                    substituteParamsInPlace(elem, params);
                }
            }
        }
    }

    /**
     * Replace every {@code {{key}}} occurrence in {@code s} with {@code params.get(key)}
     * (or leave the literal token if the param is missing - a missing param surfaces
     * visibly). Returns the original string when no token is present.
     */
    @Nonnull
    public static String substituteString(@Nonnull String s, @Nonnull JsonObject params) {
        if (s.indexOf("{{") < 0) return s;
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            int open = s.indexOf("{{", i);
            if (open < 0) {
                out.append(s, i, s.length());
                break;
            }
            int close = s.indexOf("}}", open + 2);
            if (close < 0) {
                out.append(s, i, s.length());
                break;
            }
            out.append(s, i, open);
            String key = s.substring(open + 2, close).trim();
            if (params.has(key) && params.get(key).isJsonPrimitive()) {
                out.append(params.get(key).getAsString());
            } else {
                out.append(s, open, close + 2);
            }
            i = close + 2;
        }
        return out.toString();
    }

    /**
     * Recursively merge {@code source} into {@code target}: object keys merge
     * recursively, primitives + arrays replace wholesale. Mutates {@code target}.
     */
    public static void deepMergeInto(@Nonnull JsonObject target, @Nonnull JsonObject source) {
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            String key = entry.getKey();
            JsonElement srcVal = entry.getValue();
            if (srcVal.isJsonObject() && target.has(key) && target.get(key).isJsonObject()) {
                deepMergeInto(target.getAsJsonObject(key), srcVal.getAsJsonObject());
            } else {
                target.add(key, srcVal);
            }
        }
    }

    /**
     * Apply per-id deep-merge overrides into a STRING-KEYED OBJECT map (the dialogue
     * {@code Nodes} shape). For each {@code (nodeId -> partialNode)}, deep-merge the
     * scalars/objects and merge {@code Options} per {@code optionMode}; unknown id
     * -> {@code warn} + skip.
     */
    public static void mergeKeyedMap(@Nonnull JsonObject resolved, @Nonnull String mapField,
                                     @Nonnull JsonObject overrides, @Nonnull OptionMode optionMode,
                                     @Nonnull String entityLabel, @Nonnull Consumer<String> warn) {
        if (!resolved.has(mapField) || !resolved.get(mapField).isJsonObject()) {
            resolved.add(mapField, new JsonObject());
        }
        JsonObject map = resolved.getAsJsonObject(mapField);
        for (Map.Entry<String, JsonElement> entry : overrides.entrySet()) {
            String nodeId = entry.getKey();
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject override = entry.getValue().getAsJsonObject();
            if (!map.has(nodeId) || !map.get(nodeId).isJsonObject()) {
                warn.accept(entityLabel + " override key '" + nodeId
                        + "' does not match any " + mapField + " entry in template - ignored");
                continue;
            }
            JsonObject target = map.getAsJsonObject(nodeId);

            JsonArray overrideOptions = override.has("Options") && override.get("Options").isJsonArray()
                    ? override.getAsJsonArray("Options") : null;
            JsonObject scalarsAndObjects = override;
            if (overrideOptions != null) {
                scalarsAndObjects = deepClone(override);
                scalarsAndObjects.remove("Options");
            }
            deepMergeInto(target, scalarsAndObjects);

            if (overrideOptions != null) {
                mergeOptions(target, overrideOptions, optionMode);
            }
        }
    }

    private static void mergeOptions(@Nonnull JsonObject target, @Nonnull JsonArray overrideOptions,
                                     @Nonnull OptionMode optionMode) {
        if (optionMode == OptionMode.REPLACE) {
            target.add("Options", deepClone(overrideOptions));
            return;
        }
        JsonArray base = target.has("Options") && target.get("Options").isJsonArray()
                ? target.getAsJsonArray("Options") : new JsonArray();
        for (int i = 0; i < overrideOptions.size(); i++) {
            JsonElement partial = overrideOptions.get(i);
            if (i < base.size() && partial.isJsonObject() && base.get(i).isJsonObject()) {
                deepMergeInto(base.get(i).getAsJsonObject(), partial.getAsJsonObject());
            } else if (i >= base.size()) {
                base.add(partial.isJsonObject() ? deepClone(partial.getAsJsonObject()) : partial);
            }
        }
        target.add("Options", base);
    }

    @Nonnull
    private static JsonArray deepClone(@Nonnull JsonArray source) {
        return JsonParser.parseString(source.toString()).getAsJsonArray();
    }

    /**
     * Append wholly-new entries to a STRING-KEYED OBJECT map (the dialogue
     * {@code Nodes} shape). A collision with an existing id -> {@code warn} + skip.
     */
    public static void appendKeyedMap(@Nonnull JsonObject resolved, @Nonnull String mapField,
                                      @Nonnull JsonObject extras, @Nonnull String entityLabel,
                                      @Nonnull Consumer<String> warn) {
        if (!resolved.has(mapField) || !resolved.get(mapField).isJsonObject()) {
            resolved.add(mapField, new JsonObject());
        }
        JsonObject map = resolved.getAsJsonObject(mapField);
        for (Map.Entry<String, JsonElement> entry : extras.entrySet()) {
            String nodeId = entry.getKey();
            if (map.has(nodeId)) {
                warn.accept(entityLabel + " extra " + mapField + " key '" + nodeId
                        + "' collides with an existing entry - use the override path to modify it. Skipped.");
                continue;
            }
            map.add(nodeId, entry.getValue());
        }
    }
}
