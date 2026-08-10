package com.ziggfreed.common.npc.placement;

import java.util.Collections;
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
 * The open READ vocabulary a placement's {@code Requires.Conditions} evaluate over: a mod
 * registers a namespaced factor id and the number behind it, and content gates on that id with
 * no Java.
 *
 * <p>This is the half that makes conditional placement authorable at all. Without it a pack
 * author could say WHERE an NPC stands but never WHETHER, and every conditional placement would
 * have to be a Java veto in some consumer.
 *
 * <p><b>Fail-closed, deliberately.</b> An unregistered factor resolves to {@code 0}, and because a
 * {@link PlacementCondition} compares against authored bounds, a gate written as {@code Min: 1}
 * then simply does not pass. A placement gated on a mod that is not installed stays absent rather
 * than appearing unconditionally. A provider that throws is treated the same way (guarded, warned
 * once per id, resolved to 0), so a broken third-party provider can never take a sweep down.
 *
 * <p>JVM-global static, like every other cross-mod registry here: the vocabulary is shared across
 * whichever mods are installed in one server process. Registration is idempotent per id with
 * last-write-wins; ids are matched case-insensitively.
 */
public final class PlacementFactorRegistry {

    /** What a factor provider is asked. Field-additive: a new field never breaks an implementer. */
    public record FactorContext(@Nonnull String placementId,
                                @Nullable String param,
                                @Nullable World world,
                                @Nullable Store<EntityStore> store) {
    }

    /** Resolves one factor id to a number. Never throws (the registry guards anyway). */
    @FunctionalInterface
    public interface FactorProvider {
        double resolve(@Nonnull FactorContext ctx);
    }

    private static final Map<String, FactorProvider> PROVIDERS = new ConcurrentHashMap<>();

    /** Ids already warned about, so an unregistered factor logs once rather than once per sweep. */
    private static final Map<String, Boolean> WARNED = new ConcurrentHashMap<>();

    private PlacementFactorRegistry() {
    }

    /**
     * Register (or replace) the provider for {@code factorId}. Call once from a consumer's plugin
     * {@code setup()}. A blank id is ignored.
     */
    public static void register(@Nullable String factorId, @Nullable FactorProvider provider) {
        if (factorId == null || factorId.isBlank() || provider == null) {
            return;
        }
        String key = normalize(factorId);
        PROVIDERS.put(key, provider);
        WARNED.remove(key);
    }

    /** Is {@code factorId} registered? Used by the validator to report a gate that fails closed. */
    public static boolean isRegistered(@Nullable String factorId) {
        return factorId != null && !factorId.isBlank() && PROVIDERS.containsKey(normalize(factorId));
    }

    /** Every registered factor id, sorted (diagnostics, an authoring dropdown, a validator hint). */
    @Nonnull
    public static List<String> registeredIds() {
        return PROVIDERS.keySet().stream().sorted().toList();
    }

    /**
     * Resolve {@code factorId} in {@code ctx}, or {@code 0} when nothing is registered for it or
     * the provider failed. Never throws.
     */
    public static double resolve(@Nullable String factorId, @Nonnull FactorContext ctx) {
        if (factorId == null || factorId.isBlank()) {
            return 0.0;
        }
        String key = normalize(factorId);
        FactorProvider provider = PROVIDERS.get(key);
        if (provider == null) {
            warnOnce(key, "no provider registered for placement factor '" + factorId
                    + "' - conditions on it fail closed");
            return 0.0;
        }
        try {
            double value = provider.resolve(ctx);
            return Double.isFinite(value) ? value : 0.0;
        } catch (Throwable t) {
            warnOnce(key, "placement factor provider '" + factorId + "' failed: " + t.getMessage());
            return 0.0;
        }
    }

    /**
     * Evaluate a whole {@code Requires} block: every condition must pass. A blank condition (no
     * factor id) is skipped rather than failing the gate, so a half-authored entry does not hide
     * an otherwise working placement. Returns the FIRST failing condition's factor id, or
     * {@code null} when everything passed - the caller turns that into a gate reason.
     */
    @Nullable
    public static String firstFailure(@Nullable NpcPlacementAsset.Requires requires, @Nonnull String placementId,
            @Nullable World world, @Nullable Store<EntityStore> store) {
        if (requires == null) {
            return null;
        }
        for (PlacementCondition condition : requires.conditionsOrEmpty()) {
            if (condition == null || condition.isBlank()) {
                continue;
            }
            FactorContext ctx = new FactorContext(placementId, condition.getParam(), world, store);
            if (!condition.accepts(resolve(condition.getFactor(), ctx))) {
                return condition.getFactor();
            }
        }
        return null;
    }

    /** Drop every registration. Tests only; a live server registers once and never unregisters. */
    static void clearForTests() {
        PROVIDERS.clear();
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

    /** The registered ids as an unmodifiable view (diagnostics). */
    @Nonnull
    static Map<String, FactorProvider> view() {
        return Collections.unmodifiableMap(PROVIDERS);
    }
}
