package com.ziggfreed.common.npc.placement;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The pure snapshot of what a placed NPC believes it is: which placement put it there, which
 * anchor instance it occupies, and the world identity it was placed under.
 *
 * <p>This is the decision input the reconciler's resident policy runs on, deliberately separated
 * from {@link PlacedNpcComponent} so the policy is unit-testable without a live component
 * registry.
 *
 * @param placementId  the placement this NPC belongs to, lower-cased
 * @param namespace    the owning mod's namespace, purely for diagnostics and per-mod listing
 * @param selectorName the world selector name the placement matched on, recorded so a later sweep
 *                     can tell "the rule changed" apart from "this is a different world"
 * @param anchorKey    the {@link AnchorPosition#anchorKey()} of the instance this NPC occupies
 * @param keepAlive    whether this NPC's chunk was pinned when it was placed
 * @param spawnedAtMs  epoch millis at placement, for age heuristics and diagnostics
 */
public record PlacedNpcIdentity(@Nonnull String placementId,
                                @Nonnull String namespace,
                                @Nonnull String selectorName,
                                @Nonnull String anchorKey,
                                boolean keepAlive,
                                long spawnedAtMs) {

    /** The identity of an NPC whose component held nothing usable. */
    public static final PlacedNpcIdentity UNKNOWN = new PlacedNpcIdentity("", "", "", "", false, 0L);

    @Nonnull
    public static PlacedNpcIdentity of(@Nullable String placementId, @Nullable String namespace,
            @Nullable String selectorName, @Nullable String anchorKey, boolean keepAlive, long spawnedAtMs) {
        return new PlacedNpcIdentity(
                orEmpty(placementId), orEmpty(namespace), orEmpty(selectorName), orEmpty(anchorKey),
                keepAlive, spawnedAtMs);
    }

    /** True when this NPC does not know which placement it came from, so nothing can be decided about it. */
    public boolean isUnknown() {
        return placementId.isEmpty();
    }

    @Nonnull
    private static String orEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }
}
