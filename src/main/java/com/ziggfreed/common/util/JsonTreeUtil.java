package com.ziggfreed.common.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * Pure, mod-agnostic JSON-tree primitives shared by every {@code extends}/{@code params}
 * template resolver in the Ziggfreed family (the dialogue resolver here in common, and the
 * MMO's non-dialogue content resolvers). The single home for these four operations so they
 * are implemented ONCE rather than copied per consumer:
 *
 * <ul>
 *   <li>{@link #deepClone(JsonObject)} - JSON round-trip clone (Gson exposes no public deep-copy),
 *       used at the top of each resolution so cached template payloads are never mutated.</li>
 *   <li>{@link #substituteString(String, JsonObject)} - replace {@code {{key}}} tokens in one string.</li>
 *   <li>{@link #substituteParamsInPlace(JsonElement, JsonObject)} - recursive walk applying
 *       {@link #substituteString} to every string value, with the "empty resolve drops the holding
 *       key" rule that lets a template expose optional fields a caller omits via an empty param.</li>
 *   <li>{@link #deepMergeInto(JsonObject, JsonObject)} - recursive object-key merge; primitives +
 *       arrays replace wholesale.</li>
 * </ul>
 *
 * Only Gson is a dependency; nothing here touches the engine, so it is freely unit-testable and
 * reusable by any consumer's domain-specific resolver (which keeps only its own override/extra shape).
 */
public final class JsonTreeUtil {

    private JsonTreeUtil() {
    }

    /** Deep-clone {@code source} via a JSON round-trip (Gson exposes no public deep-copy). */
    @Nonnull
    public static JsonObject deepClone(@Nonnull JsonObject source) {
        return JsonParser.parseString(source.toString()).getAsJsonObject();
    }

    /**
     * Walk {@code node} recursively, replacing {@code {{key}}} substrings in string values with
     * {@code params.get(key)}. Mutates in place. A string whose tokens all resolved to empty drops
     * its holding key (templates expose optional fields a caller omits via an empty param).
     */
    public static void substituteParamsInPlace(@Nonnull JsonElement node, @Nonnull JsonObject params) {
        if (node.isJsonObject()) {
            JsonObject obj = node.getAsJsonObject();
            List<String> keysToDrop = new ArrayList<>();
            // Materialize entries to avoid concurrent-modification during recursion.
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
     * Replace every {@code {{key}}} occurrence in {@code s} with {@code params.get(key)} (or leave
     * the literal token if the param is missing - a missing param surfaces visibly rather than being
     * silently dropped). Returns the original string when no token is present.
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
     * Recursively merge {@code source} into {@code target}: object keys merge recursively, primitives
     * + arrays replace wholesale. Mutates {@code target}.
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
}
