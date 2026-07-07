package com.ziggfreed.common.codec;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonObject;
import com.ziggfreed.common.util.JsonTreeUtil;

/**
 * Generic parent-chain resolution over a keyed RAW-JSON pool - the pre-decode half of
 * "native-lite {@code Parent} inheritance" for any asset family whose bodies must merge
 * ACROSS layers the engine store cannot see (jar + pack bodies from an asset store PLUS
 * owner-dir files read straight off disk). The engine's own {@code Parent} resolution only
 * runs inside one store's load batch, so a cross-layer type pre-merges here and then
 * decodes each resolved body through its ONE structured codec.
 *
 * <p>For each requested id: deep-clone the body, read + strip its {@code parentKey}
 * (recursively resolving that parent first, from the same pool, memoized), then deep-merge
 * the child OVER the resolved parent per leaf via {@link JsonTreeUtil#deepMergeInto}
 * (object keys merge recursively; primitives + ARRAYS replace wholesale). Multi-level
 * chains resolve transitively; a cycle or an unknown parent warns and the body resolves
 * standalone. Ids (pool keys AND parent references) are lower-cased throughout.
 *
 * <p>The POOL (every body available as a parent) is separate from the OUTPUT ids (which
 * resolved bodies to return), so a consumer can keep shared base skeletons in the pool
 * without emitting them. This generalizes the dialogue engine's
 * {@code DialogueBodyResolver} inherit core (which stays as-is - it composes the merge
 * with sugar pre-passes + an engine decode); new cross-layer types use this directly.
 */
public final class JsonParentResolver {

    private JsonParentResolver() {
    }

    /**
     * @param pool      every id -&gt; raw body available as a parent target (bases + leaves)
     * @param outputIds the ids to resolve + return (a subset of, or equal to, the pool keys)
     * @param parentKey the top-level key naming the parent id (e.g. {@code "Parent"}); stripped from output
     * @param warn      warn sink (unknown parent, cycle) - never throws
     * @return id (lower-cased) -&gt; fully parent-merged body with {@code parentKey} stripped,
     *         in {@code outputIds} order; ids absent from the pool are skipped
     */
    @Nonnull
    public static Map<String, JsonObject> resolve(@Nonnull Map<String, JsonObject> pool,
                                                  @Nonnull Collection<String> outputIds,
                                                  @Nonnull String parentKey,
                                                  @Nonnull Consumer<String> warn) {
        Map<String, JsonObject> norm = new LinkedHashMap<>();
        for (Map.Entry<String, JsonObject> e : pool.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                norm.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
            }
        }
        Map<String, JsonObject> cache = new LinkedHashMap<>();
        Set<String> onStack = new HashSet<>();
        Map<String, JsonObject> out = new LinkedHashMap<>();
        for (String id : outputIds) {
            if (id == null) {
                continue;
            }
            String key = id.toLowerCase(Locale.ROOT);
            JsonObject resolved = resolveOne(key, norm, parentKey, cache, onStack, warn);
            if (resolved != null) {
                out.put(key, resolved);
            }
        }
        return out;
    }

    @Nullable
    private static JsonObject resolveOne(@Nonnull String id, @Nonnull Map<String, JsonObject> pool,
                                         @Nonnull String parentKey, @Nonnull Map<String, JsonObject> cache,
                                         @Nonnull Set<String> onStack, @Nonnull Consumer<String> warn) {
        if (cache.containsKey(id)) {
            return cache.get(id);
        }
        JsonObject raw = pool.get(id);
        if (raw == null) {
            return null;
        }
        if (!onStack.add(id)) {
            warn.accept("Parent cycle at '" + id + "' - resolving it standalone");
            return null;
        }
        try {
            JsonObject body = JsonTreeUtil.deepClone(raw);
            String parentId = null;
            if (body.has(parentKey) && body.get(parentKey).isJsonPrimitive()) {
                parentId = body.get(parentKey).getAsString().trim().toLowerCase(Locale.ROOT);
            }
            body.remove(parentKey);
            JsonObject parent = null;
            if (parentId != null && !parentId.isEmpty()) {
                parent = resolveOne(parentId, pool, parentKey, cache, onStack, warn);
                if (parent == null) {
                    warn.accept("'" + id + "' " + parentKey + " '" + parentId
                            + "' not found (or cyclic) - resolving standalone");
                }
            }
            JsonObject resolved;
            if (parent == null) {
                resolved = body;
            } else {
                resolved = JsonTreeUtil.deepClone(parent); // parent leaves first...
                JsonTreeUtil.deepMergeInto(resolved, body); // ...child overrides per leaf
            }
            cache.put(id, resolved);
            return resolved;
        } finally {
            onStack.remove(id);
        }
    }
}
