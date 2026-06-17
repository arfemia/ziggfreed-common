package com.ziggfreed.common.dialogue.template;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import javax.annotation.Nonnull;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.ziggfreed.common.dialogue.DialogueSugar;

/**
 * Pure resolver for dialogue template extension. A dialogue body may carry, at its
 * root: {@code extends} (template id), {@code params} ({@code {{name}}} fills),
 * {@code nodeOverrides} (deep-merge per node id, {@code Options} overlay by index
 * or {@code Options!} wholesale), and {@code extraNodes} (append new node ids).
 * Inside {@code Nodes}, options may use the engine's registered sugar.
 *
 * <p>Pruning (templated bodies only) is DOMAIN-AGNOSTIC: a {@code Start} candidate
 * or option carrying a {@code "PruneIfEmpty"} marker (a param name, or an array of
 * names) is dropped when any referenced param is empty/absent, then any
 * {@code Nodes} entry left unreachable from the surviving {@code Start} via
 * {@code Goto} edges is dropped. This lets ONE template expose optional branches
 * an extending dialogue activates by passing (or omitting) a param, with no
 * knowledge of WHAT the param gates (quest, feature, anything). The marker is
 * stripped from survivors before decode. Explicit (non-templated) bodies are never
 * pruned.
 *
 * <p>The engine's {@link DialogueSugar#desugar} runs LAST and ALWAYS (templated or
 * not), so every dialogue gets option shorthand.
 */
public final class DialogueTemplateResolver {

    /** Root fields consumed by the resolver, never overlaid as ordinary body fields. */
    private static final Set<String> RESERVED =
            Set.of("extends", "params", "nodeOverrides", "extraNodes");

    /** The domain-agnostic prune marker key on a Start candidate / option. */
    private static final String PRUNE_MARKER = "PruneIfEmpty";

    private DialogueTemplateResolver() {
    }

    /**
     * Resolve a dialogue body against the supplied template registry, applying the
     * engine's {@code sugar} last. NEVER returns null: a missing/unknown template
     * degrades to the body itself (with {@code extends} removed) and a warn.
     *
     * @param dialogueId the resolving dialogue's id (warn context only)
     * @param body       the raw {@code Start}/{@code Nodes} body
     * @param templates  lowercased template id -> template body
     * @param sugar      the owning engine's sugar pass (registered expanders)
     * @param warn       receives one message per resolution problem
     */
    @Nonnull
    public static JsonObject resolve(@Nonnull String dialogueId, @Nonnull JsonObject body,
                                     @Nonnull Map<String, JsonObject> templates,
                                     @Nonnull DialogueSugar sugar, @Nonnull Consumer<String> warn) {
        JsonObject src = JsonTemplateUtil.deepClone(body);
        boolean templated = src.has("extends") && src.get("extends").isJsonPrimitive();

        JsonObject params = src.has("params") && src.get("params").isJsonObject()
                ? src.getAsJsonObject("params") : new JsonObject();

        JsonObject resolved;
        if (!templated) {
            resolved = src;
        } else {
            String templateId = src.get("extends").getAsString().toLowerCase();
            JsonObject template = templates.get(templateId);
            if (template == null) {
                warn.accept("dialogue '" + dialogueId + "' extends unknown template '" + templateId
                        + "' - using its own body. Available: " + templates.keySet());
                resolved = src;
                resolved.remove("extends");
                templated = false;
            } else {
                resolved = JsonTemplateUtil.deepClone(template);
                JsonTemplateUtil.substituteParamsInPlace(resolved, params);

                for (Map.Entry<String, JsonElement> entry : src.entrySet()) {
                    if (RESERVED.contains(entry.getKey())) continue;
                    resolved.add(entry.getKey(), entry.getValue());
                }

                if (src.has("nodeOverrides") && src.get("nodeOverrides").isJsonObject()) {
                    applyNodeOverrides(resolved, src.getAsJsonObject("nodeOverrides"), dialogueId, warn);
                }
                if (src.has("extraNodes") && src.get("extraNodes").isJsonObject()) {
                    JsonTemplateUtil.appendKeyedMap(resolved, "Nodes",
                            src.getAsJsonObject("extraNodes"),
                            "dialogue '" + dialogueId + "' extraNodes", warn);
                }
            }
        }

        // Sugar ALWAYS, last (before prune so the reachability walk sees canonical Goto actions).
        sugar.desugar(resolved);

        if (templated) {
            prune(resolved, params);
        }
        stripMarkers(resolved);
        return resolved;
    }

    private static void applyNodeOverrides(@Nonnull JsonObject resolved, @Nonnull JsonObject nodeOverrides,
                                           @Nonnull String dialogueId, @Nonnull Consumer<String> warn) {
        JsonObject mergeByIndex = new JsonObject();
        JsonObject replace = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : nodeOverrides.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                mergeByIndex.add(entry.getKey(), entry.getValue());
                continue;
            }
            JsonObject node = entry.getValue().getAsJsonObject();
            if (node.has("Options!")) {
                JsonObject renamed = JsonTemplateUtil.deepClone(node);
                renamed.add("Options", renamed.get("Options!"));
                renamed.remove("Options!");
                replace.add(entry.getKey(), renamed);
            } else {
                mergeByIndex.add(entry.getKey(), node);
            }
        }
        String label = "dialogue '" + dialogueId + "' nodeOverrides";
        if (!mergeByIndex.isEmpty()) {
            JsonTemplateUtil.mergeKeyedMap(resolved, "Nodes", mergeByIndex,
                    JsonTemplateUtil.OptionMode.MERGE_BY_INDEX, label, warn);
        }
        if (!replace.isEmpty()) {
            JsonTemplateUtil.mergeKeyedMap(resolved, "Nodes", replace,
                    JsonTemplateUtil.OptionMode.REPLACE, label, warn);
        }
    }

    /**
     * Pruning (templated only): drop {@code Start} candidates and {@code Options}
     * whose {@code PruneIfEmpty} param(s) are empty/absent, then drop any
     * {@code Nodes} entry no longer reachable from the surviving {@code Start} via
     * {@code Goto} edges.
     */
    private static void prune(@Nonnull JsonObject resolved, @Nonnull JsonObject params) {
        // (a) drop pruned Start candidates.
        if (resolved.has("Start") && resolved.get("Start").isJsonArray()) {
            JsonArray start = resolved.getAsJsonArray("Start");
            JsonArray kept = new JsonArray();
            for (JsonElement candEl : start) {
                if (!candEl.isJsonObject() || !markerEmpty(candEl.getAsJsonObject(), params)) {
                    kept.add(candEl);
                }
            }
            resolved.add("Start", kept);
        }

        if (!resolved.has("Nodes") || !resolved.get("Nodes").isJsonObject()) {
            return;
        }
        JsonObject nodes = resolved.getAsJsonObject("Nodes");

        // (a2) drop pruned options.
        for (Map.Entry<String, JsonElement> e : nodes.entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            JsonObject node = e.getValue().getAsJsonObject();
            if (!node.has("Options") || !node.get("Options").isJsonArray()) continue;
            JsonArray keptOpts = new JsonArray();
            for (JsonElement optEl : node.getAsJsonArray("Options")) {
                if (!optEl.isJsonObject() || !markerEmpty(optEl.getAsJsonObject(), params)) {
                    keptOpts.add(optEl);
                }
            }
            node.add("Options", keptOpts);
        }

        // (b) reachability from the surviving Start entry nodes.
        Set<String> reachable = new HashSet<>();
        Deque<String> frontier = new ArrayDeque<>();
        if (resolved.has("Start") && resolved.get("Start").isJsonArray()) {
            for (JsonElement candEl : resolved.getAsJsonArray("Start")) {
                if (!candEl.isJsonObject()) continue;
                JsonObject cand = candEl.getAsJsonObject();
                String node = cand.has("Node") && cand.get("Node").isJsonPrimitive()
                        ? cand.get("Node").getAsString() : null;
                if (node != null && nodes.has(node) && reachable.add(node)) {
                    frontier.add(node);
                }
            }
        }
        while (!frontier.isEmpty()) {
            JsonElement nodeEl = nodes.get(frontier.poll());
            if (nodeEl == null || !nodeEl.isJsonObject()) continue;
            for (String target : gotoTargets(nodeEl.getAsJsonObject())) {
                if (nodes.has(target) && reachable.add(target)) {
                    frontier.add(target);
                }
            }
        }

        // (c) drop unreachable nodes.
        List<String> drop = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : nodes.entrySet()) {
            if (!reachable.contains(e.getKey())) {
                drop.add(e.getKey());
            }
        }
        for (String id : drop) {
            nodes.remove(id);
        }
    }

    /**
     * True when {@code obj} carries a {@code PruneIfEmpty} marker referencing any
     * param that is empty or absent in {@code params}. The marker is a single param
     * NAME (a plain string) or an array of names.
     */
    private static boolean markerEmpty(@Nonnull JsonObject obj, @Nonnull JsonObject params) {
        if (!obj.has(PRUNE_MARKER)) {
            return false;
        }
        for (String name : markerNames(obj.get(PRUNE_MARKER))) {
            if (name.isBlank()) {
                continue;
            }
            if (!params.has(name) || !params.get(name).isJsonPrimitive()
                    || params.get(name).getAsString().isBlank()) {
                return true;
            }
        }
        return false;
    }

    @Nonnull
    private static List<String> markerNames(@Nonnull JsonElement marker) {
        List<String> names = new ArrayList<>();
        if (marker.isJsonPrimitive() && marker.getAsJsonPrimitive().isString()) {
            names.add(marker.getAsString());
        } else if (marker.isJsonArray()) {
            for (JsonElement e : marker.getAsJsonArray()) {
                if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
                    names.add(e.getAsString());
                }
            }
        }
        return names;
    }

    /** Remove the prune marker from every Start candidate and option so the codec never sees it. */
    private static void stripMarkers(@Nonnull JsonObject resolved) {
        if (resolved.has("Start") && resolved.get("Start").isJsonArray()) {
            for (JsonElement candEl : resolved.getAsJsonArray("Start")) {
                if (candEl.isJsonObject()) {
                    candEl.getAsJsonObject().remove(PRUNE_MARKER);
                }
            }
        }
        if (resolved.has("Nodes") && resolved.get("Nodes").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : resolved.getAsJsonObject("Nodes").entrySet()) {
                if (!e.getValue().isJsonObject()) continue;
                JsonObject node = e.getValue().getAsJsonObject();
                if (!node.has("Options") || !node.get("Options").isJsonArray()) continue;
                for (JsonElement optEl : node.getAsJsonArray("Options")) {
                    if (optEl.isJsonObject()) {
                        optEl.getAsJsonObject().remove(PRUNE_MARKER);
                    }
                }
            }
        }
    }

    /** Every {@code Goto} target node id reachable from one node's options. */
    @Nonnull
    private static List<String> gotoTargets(@Nonnull JsonObject node) {
        List<String> out = new ArrayList<>();
        if (!node.has("Options") || !node.get("Options").isJsonArray()) {
            return out;
        }
        for (JsonElement optEl : node.getAsJsonArray("Options")) {
            if (!optEl.isJsonObject()) continue;
            JsonObject option = optEl.getAsJsonObject();
            if (!option.has("Actions") || !option.get("Actions").isJsonArray()) continue;
            for (JsonElement actEl : option.getAsJsonArray("Actions")) {
                if (!actEl.isJsonObject()) continue;
                JsonObject action = actEl.getAsJsonObject();
                if (action.has("Type") && "Goto".equals(asString(action.get("Type")))
                        && action.has("Node") && action.get("Node").isJsonPrimitive()) {
                    out.add(action.get("Node").getAsString());
                }
            }
        }
        return out;
    }

    private static String asString(JsonElement el) {
        return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isString() ? el.getAsString() : null;
    }
}
