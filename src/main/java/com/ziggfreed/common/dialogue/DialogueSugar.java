package com.ziggfreed.common.dialogue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The option-level sugar pass: a pre-codec Gson rewrite that turns each registered
 * {@link DialogueSugarExpander}'s flat key into canonical {@code Actions}, then
 * strips the consumed keys, so the codec / validator / executor only ever see
 * canonical {@code Actions}. The DRIVER (per-node, per-option iteration, the
 * {@code Do} escape hatch, the bare-key ordering, the strip) is generic; the TABLE
 * of keys is the registered expander set, so a consumer adds a sugar key with its
 * action.
 *
 * <p>Bare keys (no {@code Do}) expand in {@link DialogueSugarExpander#order()};
 * {@code Do} is the explicit-order escape hatch (atoms desugared in array order,
 * keys within an atom in authored order). Pure, idempotent, null-safe.
 *
 * <p>Built by {@link DialogueEngine} from the registered action types' sugar.
 */
public final class DialogueSugar {

    private final Map<String, DialogueSugarExpander> byKey;
    private final List<DialogueSugarExpander> bareOrdered;
    private final Set<String> stripKeys;

    DialogueSugar(@Nonnull Collection<DialogueSugarExpander> expanders) {
        Map<String, DialogueSugarExpander> m = new LinkedHashMap<>();
        Set<String> strip = new LinkedHashSet<>();
        for (DialogueSugarExpander e : expanders) {
            m.put(e.key(), e);
            strip.addAll(e.consumedKeys());
        }
        strip.add("Do");
        this.byKey = m;
        List<DialogueSugarExpander> ordered = new ArrayList<>(expanders);
        ordered.sort(Comparator.comparingInt(DialogueSugarExpander::order));
        this.bareOrdered = ordered;
        this.stripKeys = strip;
    }

    /** Rewrite all option-level sugar in {@code dialogueBody} into canonical {@code Actions}, in place. */
    public void desugar(@Nonnull JsonObject dialogueBody) {
        if (!dialogueBody.has("Nodes") || !dialogueBody.get("Nodes").isJsonObject()) {
            return;
        }
        JsonObject nodes = dialogueBody.getAsJsonObject("Nodes");
        for (Map.Entry<String, JsonElement> nodeEntry : nodes.entrySet()) {
            JsonElement nodeEl = nodeEntry.getValue();
            if (!nodeEl.isJsonObject()) {
                continue;
            }
            JsonObject node = nodeEl.getAsJsonObject();
            if (!node.has("Options") || !node.get("Options").isJsonArray()) {
                continue;
            }
            for (JsonElement optEl : node.getAsJsonArray("Options")) {
                if (optEl.isJsonObject()) {
                    desugarOption(optEl.getAsJsonObject());
                }
            }
        }
    }

    private void desugarOption(@Nonnull JsonObject option) {
        JsonArray actions = option.has("Actions") && option.get("Actions").isJsonArray()
                ? option.getAsJsonArray("Actions") : new JsonArray();

        if (option.has("Do") && option.get("Do").isJsonArray()) {
            // Explicit-order escape hatch: each atom desugared in array order; keys
            // within an atom in authored order. A key with no expander (a sibling
            // modifier like Once) is skipped here and consumed by its primary.
            for (JsonElement atomEl : option.getAsJsonArray("Do")) {
                if (!atomEl.isJsonObject()) {
                    continue;
                }
                JsonObject atom = atomEl.getAsJsonObject();
                for (String key : new ArrayList<>(atom.keySet())) {
                    DialogueSugarExpander e = byKey.get(key);
                    if (e != null) {
                        e.expand(atom.get(key), atom, actions);
                    }
                }
            }
        } else {
            for (DialogueSugarExpander e : bareOrdered) {
                if (option.has(e.key())) {
                    e.expand(option.get(e.key()), option, actions);
                }
            }
        }

        if (!actions.isEmpty()) {
            option.add("Actions", actions);
        }
        for (String key : stripKeys) {
            option.remove(key);
        }
    }

    // ==================== reusable expander factories ====================

    /**
     * A string-valued sugar key that expands to {@code {"Type":type,"<field>":value}}
     * (the common shape: {@code Goto->Node}, {@code Open->Target},
     * {@code Accept->Quest}). No-op when the value is not a string.
     */
    @Nonnull
    public static DialogueSugarExpander string(@Nonnull String key, int order,
                                               @Nonnull String field, @Nonnull String type) {
        return new DialogueSugarExpander() {
            @Override public String key() { return key; }
            @Override public int order() { return order; }
            @Override public void expand(JsonElement value, JsonObject option, JsonArray out) {
                String v = asString(value);
                if (v != null) {
                    out.add(action(type, field, v));
                }
            }
        };
    }

    /**
     * The {@code Talk} sugar key: an optional string value becomes a
     * {@code {"Type":"Talk"(,"Target":value)}} carrier (a non-empty string sets the
     * target). A bare {@code "Talk": ""} or {@code "Talk": true} still emits a
     * targetless Talk.
     */
    @Nonnull
    public static DialogueSugarExpander talk(@Nonnull String key, int order) {
        return new DialogueSugarExpander() {
            @Override public String key() { return key; }
            @Override public int order() { return order; }
            @Override public void expand(JsonElement value, JsonObject option, JsonArray out) {
                JsonObject talk = new JsonObject();
                talk.addProperty("Type", "Talk");
                String target = asString(value);
                if (target != null && !target.isEmpty()) {
                    talk.addProperty("Target", target);
                }
                out.add(talk);
            }
        };
    }

    /** The {@code Close} sugar key: a boolean {@code true} emits {@code {"Type":"Close"}}. */
    @Nonnull
    public static DialogueSugarExpander close(@Nonnull String key, int order) {
        return new DialogueSugarExpander() {
            @Override public String key() { return key; }
            @Override public int order() { return order; }
            @Override public void expand(JsonElement value, JsonObject option, JsonArray out) {
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean()) {
                    JsonObject close = new JsonObject();
                    close.addProperty("Type", "Close");
                    out.add(close);
                }
            }
        };
    }

    /** Build a {@code {"Type":...,"<field>":<value>}} action object. */
    @Nonnull
    public static JsonObject action(@Nonnull String type, @Nonnull String field, @Nonnull String value) {
        JsonObject obj = new JsonObject();
        obj.addProperty("Type", type);
        obj.addProperty(field, value);
        return obj;
    }

    /** The element as a non-null string when it is a string primitive, else null. */
    @Nullable
    public static String asString(@Nonnull JsonElement el) {
        return el.isJsonPrimitive() && el.getAsJsonPrimitive().isString() ? el.getAsString() : null;
    }

    /** The element as a boolean, or {@code fallback} when absent / not a boolean. */
    public static boolean asBool(@Nullable JsonElement el, boolean fallback) {
        return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean()
                ? el.getAsBoolean() : fallback;
    }
}
