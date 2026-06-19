package com.ziggfreed.common.dialogue.template;

import java.util.Map;
import java.util.function.Consumer;

import javax.annotation.Nonnull;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ziggfreed.common.util.JsonTreeUtil;

/**
 * Dialogue-specific template primitives: the string-keyed {@code Nodes} map merge/append with the
 * per-index {@code Options} overlay semantics, plus thin re-exports of the four mod-agnostic JSON
 * primitives (deep-clone, {@code {{param}}} substitution, recursive merge) that now live ONCE in
 * {@link JsonTreeUtil} so the MMO's non-dialogue resolvers and this dialogue resolver share one
 * implementation. Only the engine-jar + Gson are dependencies.
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

    /** Deep-clone {@code source}. Delegates to {@link JsonTreeUtil#deepClone(JsonObject)}. */
    @Nonnull
    public static JsonObject deepClone(@Nonnull JsonObject source) {
        return JsonTreeUtil.deepClone(source);
    }

    /** {@code {{param}}} substitution over a whole tree. Delegates to {@link JsonTreeUtil#substituteParamsInPlace}. */
    public static void substituteParamsInPlace(@Nonnull JsonElement node, @Nonnull JsonObject params) {
        JsonTreeUtil.substituteParamsInPlace(node, params);
    }

    /** {@code {{param}}} substitution over one string. Delegates to {@link JsonTreeUtil#substituteString}. */
    @Nonnull
    public static String substituteString(@Nonnull String s, @Nonnull JsonObject params) {
        return JsonTreeUtil.substituteString(s, params);
    }

    /** Recursive object-key merge. Delegates to {@link JsonTreeUtil#deepMergeInto}. */
    public static void deepMergeInto(@Nonnull JsonObject target, @Nonnull JsonObject source) {
        JsonTreeUtil.deepMergeInto(target, source);
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
