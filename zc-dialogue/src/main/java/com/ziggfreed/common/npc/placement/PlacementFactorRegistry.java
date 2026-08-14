package com.ziggfreed.common.npc.placement;

import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.factor.FactorConditions;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorProvider;
import com.ziggfreed.common.factor.FactorRegistry;
import com.ziggfreed.common.registry.RegistryLedger;

/**
 * The placement engine's slice of the shared factor vocabulary: the process-wide
 * {@link FactorRegistry} a placement's {@code Requires.Conditions} evaluate over, behind a static
 * facade because placement CONTENT is process-wide too (one asset store, one sweep, one ledger).
 * A mod registers a namespaced factor id and the number behind it, and content gates on that id
 * with no Java.
 *
 * <p>This is the half that makes conditional placement authorable at all. Without it a pack author
 * could say WHERE an NPC stands but never WHETHER, and every conditional placement would have to
 * be a Java veto in some consumer.
 *
 * <p><b>The gate never silently opens.</b> An unregistered factor, a throwing provider, and a
 * provider that cannot answer all resolve to {@code null}, and a
 * {@code com.ziggfreed.common.factor.FactorCondition} rejects null whatever its bounds say - so a
 * placement gated on a mod that is not installed stays absent rather than appearing
 * unconditionally, INCLUDING the bounds-less presence-check form. That is the whole reason the
 * resolution is a nullable Double rather than a number with a zero default: "the factor's owner is
 * not here" and "the value is zero" are different facts and only one of them may open a gate.
 * {@link NpcPlacementValidator} reports an unregistered factor so the fail-closed case is visible
 * at authoring time instead of only as a missing NPC.
 *
 * <p>Registration is idempotent per id with last-write-wins; ids are matched case-insensitively;
 * bookkeeping (who owns an id, how often its provider failed) rides the shared
 * {@link RegistryLedger} inside the {@link FactorRegistry}, labelled {@code placement} so its
 * warnings name this engine. The registry lives in zc-core, so its ledger is the base class rather
 * than {@link PlacementRegistryLedger}; that subclass backs {@link AnchorResolverRegistry}, which
 * holds its own ledger directly.
 */
public final class PlacementFactorRegistry {

    @Nonnull
    private static final FactorRegistry REGISTRY = new FactorRegistry("placement");

    private PlacementFactorRegistry() {
    }

    /** The underlying registry, for a caller that evaluates conditions itself. */
    @Nonnull
    public static FactorRegistry registry() {
        return REGISTRY;
    }

    /**
     * Register (or replace) the provider for {@code factorId}. Call once from a consumer's plugin
     * {@code setup()}. A blank id is ignored.
     */
    public static void register(@Nullable String factorId, @Nullable FactorProvider provider) {
        register(factorId, RegistryLedger.UNATTRIBUTED, provider);
    }

    /** As {@link #register(String, FactorProvider)}, attributing the claim to {@code owner}. */
    public static void register(@Nullable String factorId, @Nullable String owner, @Nullable FactorProvider provider) {
        REGISTRY.register(factorId, owner, provider);
    }

    /** Is {@code factorId} registered? Used by the validator to report a gate that fails closed. */
    public static boolean isRegistered(@Nullable String factorId) {
        return REGISTRY.isRegistered(factorId);
    }

    /** Every registered factor id, sorted (diagnostics, an authoring dropdown, a validator hint). */
    @Nonnull
    public static List<String> registeredIds() {
        return REGISTRY.ids();
    }

    /** Every registered factor id's owner + failure history, keyed by id (an admin channels-list read). */
    @Nonnull
    public static Map<String, PlacementRegistryLedger.RegistrationInfo> info() {
        return REGISTRY.info();
    }

    /**
     * Resolve {@code factorId} in {@code ctx}, or {@code null} when nothing is registered for it,
     * the provider could not answer, or it failed. Never throws.
     */
    @Nullable
    public static Double resolve(@Nullable String factorId, @Nonnull FactorContext ctx) {
        return REGISTRY.resolve(factorId, ctx);
    }

    /**
     * Evaluate a whole {@code Requires} block: every condition must pass. A blank condition (no
     * factor id) is skipped rather than failing the gate, so a half-authored entry does not hide
     * an otherwise working placement. Returns the FIRST failing condition's factor id, or
     * {@code null} when everything passed - the caller turns that into a gate reason.
     *
     * <p>The context carries the placement id as its opaque {@code payload}, so a provider that
     * genuinely needs to know WHICH placement it is being asked about reads
     * {@code ctx.payload(String.class)}; there is no subject entity, because a placement gate is
     * asked before anything stands there to ask about.
     */
    @Nullable
    public static String firstFailure(@Nullable NpcPlacementAsset.Requires requires, @Nonnull String placementId,
            @Nullable World world, @Nullable Store<EntityStore> store) {
        if (requires == null) {
            return null;
        }
        FactorContext ctx = FactorContext.builder()
                .payload(placementId)
                .world(world)
                .store(store)
                .build();
        return FactorConditions.firstFailure(requires.conditionsOrEmpty(), REGISTRY, ctx);
    }

    /** Drop every registration. Tests only; a live server registers once and never unregisters. */
    static void clearForTests() {
        REGISTRY.clear();
    }
}
