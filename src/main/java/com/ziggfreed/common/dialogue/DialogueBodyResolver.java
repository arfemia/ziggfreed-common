package com.ziggfreed.common.dialogue;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Resolves raw dialogue bodies (a {@code Start}/{@code Nodes} JSON object that may
 * carry a top-level {@code Parent} id) into decoded {@link NpcDialogue}s using NATIVE
 * codec inheritance - the native-lite replacement for the old {@code extends}/
 * {@code params}/{@code nodeOverrides}/prune template DSL.
 *
 * <p>For each requested id it: deep-clones the body, reads + strips its {@code Parent}
 * (recursively resolving that parent first, from the same pool), runs the engine's
 * option sugar pre-pass, then decodes through {@link DialogueEngine#decodeWithParent}
 * so the engine's real {@code decodeAndInheritJson} merges child over parent (child
 * fields override; {@code Nodes} keyed-merges via {@code InheritMapCodec}; an omitted
 * {@code Start}/{@code Nodes} inherits). Multi-level parent chains resolve transitively
 * with memoization; a cycle is broken with a warning.
 *
 * <p>The POOL (all bodies available as parents) is separate from the OUTPUT ids (which
 * decoded dialogues to return), so a consumer can keep shared base skeletons in the pool
 * (e.g. a {@code QuestGiver_Standard} base, or another owner's dialogues) without
 * emitting them. Ids are lower-cased throughout.
 */
public final class DialogueBodyResolver {

    private DialogueBodyResolver() {
    }

    /**
     * @param pool      every id -&gt; raw body available as a {@code Parent} target (bases + dialogues)
     * @param outputIds the ids to decode + return (a subset of, or equal to, the pool keys)
     * @param engine    the consumer's built engine (its registered domain + sugar)
     * @param warn      warn sink (unresolved parent, cycle, decode failure) - never throws
     * @return id -&gt; decoded dialogue for the requested output ids (lower-cased), a fresh snapshot
     */
    @Nonnull
    public static Map<String, NpcDialogue> resolve(@Nonnull Map<String, JsonObject> pool,
                                                   @Nonnull Collection<String> outputIds,
                                                   @Nonnull DialogueEngine engine,
                                                   @Nonnull Consumer<String> warn) {
        Map<String, JsonObject> norm = new LinkedHashMap<>();
        for (Map.Entry<String, JsonObject> e : pool.entrySet()) {
            if (e.getValue() != null) {
                norm.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
            }
        }
        Map<String, NpcDialogue> cache = new HashMap<>();
        Set<String> onStack = new HashSet<>();
        Map<String, NpcDialogue> out = new LinkedHashMap<>();
        for (String id : outputIds) {
            NpcDialogue d = resolveOne(id.toLowerCase(Locale.ROOT), norm, engine, cache, onStack, warn);
            if (d != null) {
                out.put(d.getId(), d);
            }
        }
        return out;
    }

    /**
     * Expand shared OPTION FRAGMENTS (pre-decode, pre-sugar). A body may declare a top-level
     * {@code "Fragments": { "<id>": [ <option>, ... ] }} map of reusable option lists; a node
     * references one or more with {@code "IncludeOptions": ["<id>", ...]}, and each referenced
     * fragment's options are DEEP-COPIED and appended AFTER the node's own {@code Options} (so a
     * node's own options lead, the shared footer trails). The {@code Fragments} map + every
     * {@code IncludeOptions} key are stripped before decode so the codec never sees them. This is
     * the intra-dialogue DRY seam (a hub footer authored once, referenced by every steady node);
     * fragments are resolved within the SAME body (a fragment defined in a {@code Parent} is not
     * visible to a child - keep shared footers in the body that uses them, or in a base a child
     * fully inherits). An unknown fragment id warns and is skipped (the node keeps its own options).
     */
    private static void expandOptionFragments(@Nonnull String id, @Nonnull JsonObject body,
                                              @Nonnull Consumer<String> warn) {
        JsonObject fragments = body.has("Fragments") && body.get("Fragments").isJsonObject()
                ? body.getAsJsonObject("Fragments") : null;
        if (body.has("Nodes") && body.get("Nodes").isJsonObject()) {
            JsonObject nodes = body.getAsJsonObject("Nodes");
            for (Map.Entry<String, JsonElement> e : nodes.entrySet()) {
                if (!e.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject node = e.getValue().getAsJsonObject();
                if (!node.has("IncludeOptions")) {
                    continue;
                }
                JsonElement inc = node.get("IncludeOptions");
                node.remove("IncludeOptions");
                if (!inc.isJsonArray()) {
                    continue;
                }
                JsonArray options = node.has("Options") && node.get("Options").isJsonArray()
                        ? node.getAsJsonArray("Options") : new JsonArray();
                for (JsonElement fidEl : inc.getAsJsonArray()) {
                    if (!fidEl.isJsonPrimitive()) {
                        continue;
                    }
                    String fid = fidEl.getAsString();
                    JsonElement frag = fragments != null ? fragments.get(fid) : null;
                    if (frag == null || !frag.isJsonArray()) {
                        warn.accept("Dialogue '" + id + "' node '" + e.getKey()
                                + "' IncludeOptions references unknown fragment '" + fid + "'");
                        continue;
                    }
                    for (JsonElement opt : frag.getAsJsonArray()) {
                        options.add(opt.deepCopy());
                    }
                }
                node.add("Options", options);
            }
        }
        body.remove("Fragments");
    }

    @Nullable
    private static NpcDialogue resolveOne(@Nonnull String id, @Nonnull Map<String, JsonObject> pool,
                                          @Nonnull DialogueEngine engine, @Nonnull Map<String, NpcDialogue> cache,
                                          @Nonnull Set<String> onStack, @Nonnull Consumer<String> warn) {
        if (cache.containsKey(id)) {
            return cache.get(id);
        }
        JsonObject raw = pool.get(id);
        if (raw == null) {
            return null;
        }
        if (!onStack.add(id)) {
            warn.accept("Dialogue parent cycle at '" + id + "' - decoding it standalone");
            return null;
        }
        try {
            JsonObject body = JsonParser.parseString(raw.toString()).getAsJsonObject(); // deep clone
            String parentId = null;
            if (body.has("Parent") && body.get("Parent").isJsonPrimitive()) {
                parentId = body.get("Parent").getAsString().toLowerCase(Locale.ROOT);
                body.remove("Parent");
            }
            NpcDialogue parent = null;
            if (parentId != null) {
                parent = resolveOne(parentId, pool, engine, cache, onStack, warn);
                if (parent == null) {
                    warn.accept("Dialogue '" + id + "' Parent '" + parentId
                            + "' not found (or cyclic) - decoding standalone");
                }
            }
            expandOptionFragments(id, body, warn); // splice named shared option fragments into nodes (pre-sugar)
            engine.sugar().desugar(body); // option shorthand -> canonical Actions (pre-decode)
            NpcDialogue d = engine.decodeWithParent(id, body.toString(), parent);
            cache.put(id, d);
            return d;
        } catch (Exception e) {
            warn.accept("Skipping dialogue '" + id + "': " + e.getMessage());
            cache.put(id, null);
            return null;
        } finally {
            onStack.remove(id);
        }
    }
}
