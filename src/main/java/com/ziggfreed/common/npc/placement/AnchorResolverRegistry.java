package com.ziggfreed.common.npc.placement;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * The open extension point for WHERE an NPC can stand: a mod registers a namespaced provider id
 * and the positions behind it, and content addresses it through
 * {@code Anchor.Custom { "Provider": "<id>", "Params": {...} }} with no change to this library.
 *
 * <p>The five built-in anchor groups cover the cases this library can compute for itself (a world
 * spawn point, fixed coordinates, a worldgen marker, a discovered zone). Everything else - a
 * station block, a boss room, a guild hall a mod places itself - is a position only its owner
 * knows about, and this registry is how that owner contributes it without the schema growing a
 * group per mod.
 *
 * <p><b>An unregistered provider yields NO position</b>, so the placement stays absent; it is
 * never a crash, and {@link NpcPlacementValidator} reports it so the silence is visible at
 * authoring time. A provider that throws is guarded the same way.
 *
 * <p>A provider may return several positions. Each becomes an independent placement instance, so
 * its {@link AnchorPosition#instanceId()} must be STABLE across restarts: an id derived from the
 * position's own identity (a block key, a room id) keeps the ledger row matching, while a bare
 * loop index changes whenever the provider's ordering changes and mints a duplicate NPC.
 */
public final class AnchorResolverRegistry {

    /** What a resolver is asked for. Field-additive: a new field never breaks an implementer. */
    public record AnchorRequest(@Nonnull String placementId,
                                @Nonnull World world,
                                @Nonnull Store<EntityStore> store,
                                @Nonnull Map<String, String> params) {
    }

    /** Resolves a custom anchor to zero or more positions. Never throws (the registry guards anyway). */
    @FunctionalInterface
    public interface AnchorResolver {
        @Nonnull
        List<AnchorPosition> resolve(@Nonnull AnchorRequest request);
    }

    private static final Map<String, AnchorResolver> RESOLVERS = new ConcurrentHashMap<>();

    /** Providers already warned about, so an unregistered id logs once rather than once per sweep. */
    private static final Map<String, Boolean> WARNED = new ConcurrentHashMap<>();

    private AnchorResolverRegistry() {
    }

    /**
     * Register (or replace) the resolver for {@code providerId}. Call once from a consumer's
     * plugin {@code setup()}. A blank id is ignored.
     */
    public static void register(@Nullable String providerId, @Nullable AnchorResolver resolver) {
        if (providerId == null || providerId.isBlank() || resolver == null) {
            return;
        }
        String key = normalize(providerId);
        RESOLVERS.put(key, resolver);
        WARNED.remove(key);
    }

    /** Is {@code providerId} registered? Used by the validator to report an anchor that resolves to nothing. */
    public static boolean isRegistered(@Nullable String providerId) {
        return providerId != null && !providerId.isBlank() && RESOLVERS.containsKey(normalize(providerId));
    }

    /** Every registered provider id, sorted (diagnostics, an authoring hint). */
    @Nonnull
    public static List<String> registeredIds() {
        return RESOLVERS.keySet().stream().sorted().toList();
    }

    /**
     * Resolve a custom anchor. Returns an EMPTY list when the provider is unregistered, returned
     * null, or threw. The positions are re-stamped with {@link AnchorPosition.AnchorKind#CUSTOM}
     * and a provider-prefixed instance id, so two providers contributing to one placement can
     * never collide on an anchor key.
     */
    @Nonnull
    public static List<AnchorPosition> resolve(@Nullable String providerId, @Nonnull AnchorRequest request) {
        if (providerId == null || providerId.isBlank()) {
            return List.of();
        }
        String key = normalize(providerId);
        AnchorResolver resolver = RESOLVERS.get(key);
        if (resolver == null) {
            warnOnce(key, "no anchor resolver registered for provider '" + providerId
                    + "' - placements anchored to it will not appear");
            return List.of();
        }
        try {
            List<AnchorPosition> raw = resolver.resolve(request);
            if (raw == null || raw.isEmpty()) {
                return List.of();
            }
            return raw.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(p -> new AnchorPosition(AnchorPosition.AnchorKind.CUSTOM,
                            key + "#" + p.instanceId(), p.x(), p.y(), p.z(), p.yaw()))
                    .toList();
        } catch (Throwable t) {
            warnOnce(key, "anchor resolver '" + providerId + "' failed: " + t.getMessage());
            return List.of();
        }
    }

    /** Drop every registration. Tests only. */
    static void clearForTests() {
        RESOLVERS.clear();
        WARNED.clear();
    }

    @Nonnull
    private static String normalize(@Nonnull String id) {
        return id.trim().toLowerCase(Locale.ROOT);
    }

    private static void warnOnce(@Nonnull String key, @Nonnull String message) {
        if (WARNED.putIfAbsent(key, Boolean.TRUE) == null) {
            SafeLog.warn("[placement] " + message);
        }
    }
}
