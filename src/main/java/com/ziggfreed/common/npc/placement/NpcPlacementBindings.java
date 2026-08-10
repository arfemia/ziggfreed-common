package com.ziggfreed.common.npc.placement;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * The open WRITE vocabulary of a placement: a mod claims a namespace, and every
 * {@code Interact.Bindings} entry authored on that namespace is forwarded to it verbatim.
 *
 * <p>This library never interprets a channel. It reads the map, splits each key on its
 * {@code namespace:channel} colon, groups the entries by namespace, and hands each group to
 * whoever registered that namespace. What {@code "yourmod:ui_target": {"Value": "hub"}} means is
 * entirely yours, which is what lets a placement asset carry your vocabulary without this schema
 * learning it.
 *
 * <p><b>An unclaimed namespace is one WARN and then silence.</b> A server that removed a mod
 * whose bindings are still authored keeps working, its NPCs simply do less; a hard failure there
 * would take down every placement in the file for a channel that is by definition optional.
 *
 * <p>JVM-global static, so the fan-out reaches every installed consumer in one process.
 * Registration is idempotent per namespace with last-write-wins; namespaces match
 * case-insensitively.
 */
public final class NpcPlacementBindings {

    private static final Map<String, PlacementInteractHandler> HANDLERS = new ConcurrentHashMap<>();

    /** Namespaces already warned about, so an unclaimed channel logs once, not once per press-F. */
    private static final Map<String, Boolean> WARNED = new ConcurrentHashMap<>();

    private NpcPlacementBindings() {
    }

    /**
     * Claim {@code namespace} (the part before the colon in a channel id) and handle every
     * binding authored on it. Call once from a consumer's plugin {@code setup()}.
     */
    public static void register(@Nullable String namespace, @Nullable PlacementInteractHandler handler) {
        if (namespace == null || namespace.isBlank() || handler == null) {
            return;
        }
        String key = normalize(namespace);
        HANDLERS.put(key, handler);
        WARNED.remove(key);
    }

    /** Is {@code namespace} claimed? */
    public static boolean isRegistered(@Nullable String namespace) {
        return namespace != null && !namespace.isBlank() && HANDLERS.containsKey(normalize(namespace));
    }

    /** Every claimed namespace, sorted (diagnostics, a validator hint). */
    @Nonnull
    public static List<String> registeredNamespaces() {
        return HANDLERS.keySet().stream().sorted().toList();
    }

    /**
     * Every binding a placement authored, keyed by full channel id. Empty when the placement is
     * unknown or authored none. This is the read a consumer uses OUTSIDE a press-F (for example
     * to answer "which placement carries npc_id = guide" when matching a quest objective).
     */
    @Nonnull
    public static Map<String, PlacementBinding> bindingsFor(@Nullable String placementId) {
        if (placementId == null || placementId.isBlank()) {
            return Map.of();
        }
        NpcPlacementAsset placement = NpcPlacementConfig.getInstance().resolve(placementId);
        if (placement == null || placement.getInteract() == null) {
            return Map.of();
        }
        return placement.getInteract().getBindings();
    }

    /**
     * The {@code Value} of one channel on one placement, or {@code null}. The convenience the
     * common "which id does this NPC answer to" lookup collapses to.
     */
    @Nullable
    public static String bindingValue(@Nullable String placementId, @Nonnull String channelId) {
        PlacementBinding binding = bindingsFor(placementId).get(channelId);
        return binding == null ? null : binding.getValue();
    }

    /**
     * Group a binding map by namespace. PURE (no engine, no registry), so the split rule is
     * unit-testable on its own. A key with no colon, or a blank namespace, is dropped: a channel
     * id without a namespace has no owner and could never be routed.
     */
    @Nonnull
    public static Map<String, Map<String, PlacementBinding>> byNamespace(
            @Nonnull Map<String, PlacementBinding> bindings) {
        Map<String, Map<String, PlacementBinding>> out = new LinkedHashMap<>();
        for (Map.Entry<String, PlacementBinding> e : bindings.entrySet()) {
            String channel = e.getKey();
            if (channel == null || e.getValue() == null) {
                continue;
            }
            int colon = channel.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String namespace = normalize(channel.substring(0, colon));
            if (namespace.isEmpty()) {
                continue;
            }
            out.computeIfAbsent(namespace, k -> new LinkedHashMap<>()).put(channel, e.getValue());
        }
        return out;
    }

    /**
     * Fan a press-F out to every claimed namespace the placement authored bindings on. Each
     * handler is individually guarded, so one bad consumer can never suppress another's effect.
     * Returns how many handlers actually ran.
     *
     * <p>World thread only (it hands the caller's live store and refs straight through).
     */
    public static int dispatchInteract(@Nonnull String placementId,
            @Nonnull Map<String, PlacementBinding> bindings,
            @Nonnull Ref<EntityStore> npcRef, @Nonnull Ref<EntityStore> playerRef,
            @Nonnull Store<EntityStore> store) {
        int dispatched = 0;
        for (Map.Entry<String, Map<String, PlacementBinding>> group : byNamespace(bindings).entrySet()) {
            String namespace = group.getKey();
            PlacementInteractHandler handler = HANDLERS.get(namespace);
            if (handler == null) {
                warnOnce(namespace, "no handler registered for binding namespace '" + namespace
                        + "' - those bindings are ignored");
                continue;
            }
            try {
                handler.onInteract(new PlacementInteractHandler.InteractContext(
                        placementId, namespace, group.getValue(), npcRef, playerRef, store));
                dispatched++;
            } catch (Throwable t) {
                SafeLog.warn("[placement] binding handler '" + namespace + "' failed for placement '"
                        + placementId + "': " + t.getMessage());
            }
        }
        return dispatched;
    }

    /** Drop every registration. Tests only. */
    static void clearForTests() {
        HANDLERS.clear();
        WARNED.clear();
    }

    @Nonnull
    private static String normalize(@Nonnull String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static void warnOnce(@Nonnull String key, @Nonnull String message) {
        if (WARNED.putIfAbsent(key, Boolean.TRUE) == null) {
            SafeLog.warn("[placement] " + message);
        }
    }
}
