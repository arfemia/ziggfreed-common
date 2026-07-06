package com.ziggfreed.common.dialogue.asset;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonObject;
import com.ziggfreed.common.ZiggfreedCommonPlugin;
import com.ziggfreed.common.dialogue.DialogueBodyResolver;
import com.ziggfreed.common.dialogue.DialogueEngine;
import com.ziggfreed.common.dialogue.NpcDialogue;

/**
 * The mod-agnostic raw-layer holder for the dialogue stores, the Pattern-B twin of
 * {@code AbstractKeyedAssetConfig} (a raw type cannot pre-resolve to a typed model, so
 * this keeps the raw {@code Start}/{@code Nodes} bodies + the templates and resolves them
 * on demand). Lifted from hyMMO's {@code DialogueConfig}: it holds the discovered dialogue
 * bodies + their owners + the template registry, and folds them into {@link NpcDialogue}s
 * through the shared template-resolve + engine-decode path.
 *
 * <p>The two raw layers are rebuilt WHOLESALE from each load event ({@link #mergeBodies} /
 * {@link #mergeTemplates}, called by a consumer's {@code LoadedAssetsEvent} listeners via
 * {@code AssetMergeAdapter.layer}), so a hot re-import is idempotent. Resolution is
 * deferred to {@link #resolveAll}: a consumer builds its {@link DialogueEngine} (registering
 * its domain action/condition types), then calls {@code resolveAll(engine, "<owner>")} once
 * and feeds the resulting {@code id -> NpcDialogue} map to its {@code DialoguePageDeps}
 * resolver. The {@code ownerFilter} selects only that game's dialogues (null = all), so
 * several consumers can author into one shared store.
 *
 * <p>Ids are lower-cased on every layer so author casing never splits an entry. Writes are
 * synchronized; the maps are concurrent for lock-free reads.
 */
public final class DialogueAssetStore {

    private static final DialogueAssetStore INSTANCE = new DialogueAssetStore();

    @Nonnull
    public static DialogueAssetStore getInstance() {
        return INSTANCE;
    }

    /** id -> raw dialogue body ({@code Start}/{@code Nodes}, pre-resolve). */
    private final Map<String, JsonObject> bodies = new ConcurrentHashMap<>();
    /** id -> owner (which game owns the dialogue), parallel to {@link #bodies}. */
    private final Map<String, String> bodyOwners = new ConcurrentHashMap<>();
    /** lowercased template id -> raw template body. */
    private final Map<String, JsonObject> templates = new ConcurrentHashMap<>();

    private DialogueAssetStore() {
    }

    /**
     * Rebuild the dialogue body + owner layers from a load event's decoded assets.
     * Each entry's raw {@code Payload} (the {@code Start}/{@code Nodes} body) and its
     * {@code Owner} are captured under the lower-cased id; entries with no payload are
     * skipped. Idempotent on hot re-import (the whole layer is rebuilt).
     */
    public synchronized void mergeBodies(@Nonnull Map<String, ZcDialogueAsset> assetLayer) {
        bodies.clear();
        bodyOwners.clear();
        for (Map.Entry<String, ZcDialogueAsset> e : assetLayer.entrySet()) {
            ZcDialogueAsset asset = e.getValue();
            if (asset == null) {
                continue;
            }
            JsonObject body = asset.getPayloadAsJsonObject();
            if (body == null) {
                continue;
            }
            String id = e.getKey().toLowerCase(Locale.ROOT);
            bodies.put(id, body);
            String owner = asset.getOwner();
            if (owner != null && !owner.isBlank()) {
                bodyOwners.put(id, owner.toLowerCase(Locale.ROOT));
            }
        }
    }

    /**
     * Rebuild the template registry from a load event's decoded template payloads
     * ({@code id -> raw Start/Nodes body}). Templates load before the dialogues store, so
     * this is populated before any {@link #resolveAll}. Idempotent on hot re-import.
     */
    public synchronized void mergeTemplates(@Nonnull Map<String, JsonObject> templateLayer) {
        templates.clear();
        for (Map.Entry<String, JsonObject> e : templateLayer.entrySet()) {
            JsonObject body = e.getValue();
            if (body != null) {
                templates.put(e.getKey().toLowerCase(Locale.ROOT), body);
            }
        }
    }

    /**
     * Resolve every body whose owner matches {@code ownerFilter} into a decoded
     * {@link NpcDialogue} via {@link DialogueBodyResolver} - native {@code Parent}
     * inheritance ({@code decodeAndInheritJson} + keyed node merge) plus the engine's
     * option sugar pre-pass. Base skeletons (a {@code Parent} target) may live in either
     * store: the resolution POOL is the templates plus the bodies, while only owner-matched
     * BODIES are emitted, so a shared base can be authored as a template (or another
     * dialogue) and inherited by id without itself being returned. A body that decodes to
     * null, or throws, is skipped (guarded), never aborting the batch.
     *
     * @param engine      the consumer's built engine (its registered domain types)
     * @param ownerFilter only emit dialogues with this owner ({@code null} = all owners,
     *                    including unowned)
     * @return id -> decoded dialogue (lower-cased ids), a fresh snapshot
     */
    @Nonnull
    public Map<String, NpcDialogue> resolveAll(@Nonnull DialogueEngine engine, @Nullable String ownerFilter) {
        String filter = ownerFilter == null ? null : ownerFilter.toLowerCase(Locale.ROOT);
        // Pool: any template OR body can be a Parent target (a same-id body wins over a template).
        Map<String, JsonObject> pool = new LinkedHashMap<>(templates);
        pool.putAll(bodies);
        // Output: only bodies matching the owner filter.
        List<String> outputIds = new ArrayList<>();
        for (String id : bodies.keySet()) {
            if (filter == null || filter.equals(bodyOwners.get(id))) {
                outputIds.add(id);
            }
        }
        return DialogueBodyResolver.resolve(pool, outputIds, engine, WARN);
    }

    /** Unmodifiable view of the raw dialogue bodies (id -> {@code Start}/{@code Nodes}, pre-resolve). */
    @Nonnull
    public Map<String, JsonObject> rawBodies() {
        return Collections.unmodifiableMap(bodies);
    }

    /** Unmodifiable view of the effective template registry (resolver input). */
    @Nonnull
    public Map<String, JsonObject> templates() {
        return Collections.unmodifiableMap(templates);
    }

    /** Warn sink: logs through the common plugin logger, guarded for log-manager-less unit JVMs. */
    private static final java.util.function.Consumer<String> WARN = msg -> {
        try {
            ZiggfreedCommonPlugin.LOGGER.atWarning().log("[DialogueAssetStore] %s", msg);
        } catch (Throwable ignored) {
            // a unit JVM with no log manager throws an Error from the fluent logger; swallow it.
        }
    };
}
