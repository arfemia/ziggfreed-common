package com.ziggfreed.common.npc.placement.runtime;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.npc.NpcIdentities;
import com.ziggfreed.common.npc.placement.asset.NpcPlacementAsset;
import com.ziggfreed.common.npc.placement.asset.NpcPlacementConfig;
import com.ziggfreed.common.world.PlacementWheres;
import com.ziggfreed.common.world.WorldSelector;
import com.ziggfreed.common.worldmap.WaypointPosition;

/**
 * Where a character stands: under which worlds' {@code Where}, and at which remembered positions in
 * one world. The one read a waypoint makes, and what a {@code Where}'s {@code Placement} axis reads
 * ({@link #placementWheres}); every table it reads is concurrent, so the map tracker may call
 * {@link #positionsOf} and any thread may score a {@code Placement} axis.
 */
public final class PlacementWorlds {

    /** The {@code Where} an unauthored one stands in for: an exact match on {@code default}. */
    private static final WorldSelector DEFAULT_WHERE =
            WorldSelector.of(new String[]{NpcPlacementReconciler.DEFAULT_WORLD_NAME}, null, null);

    private PlacementWorlds() {
    }

    /**
     * The {@code Where} {@code placement} stands under: its own, or {@code Match ["default"]} when it authors
     * none. A {@code Placement} axis in it is dropped (no chains), so a placement naming only placements
     * stands nowhere; the validator says so ({@code PLACEMENT_CHAIN}).
     */
    @Nonnull
    public static WorldSelector effectiveWhere(@Nonnull NpcPlacementAsset placement) {
        WorldSelector where = placement.getWhere();
        return where == null || where.isBlank() ? DEFAULT_WHERE : where.withoutPlacement();
    }

    /**
     * The answer to zc-world's {@code Placement} axis ({@link PlacementWheres}): each placement's
     * {@link #effectiveWhere} as the store resolves it, the owner layer included, whether or not it is
     * enabled or its NPC stands anywhere right now; an id the store does not hold answers nothing.
     */
    @Nonnull
    public static PlacementWheres.Lookup placementWheres() {
        return new PlacementWheres.Lookup() {
            @Nullable
            @Override
            public WorldSelector whereOf(@Nonnull String placementId) {
                NpcPlacementAsset placement = NpcPlacementConfig.getInstance().resolve(placementId);
                return placement == null ? null : effectiveWhere(placement);
            }

            @Override
            public boolean folded() {
                return NpcPlacementConfig.getInstance().hasFolded();
            }

            @Override
            public boolean refused(@Nonnull String placementId) {
                return NpcPlacementConfig.getInstance().modGateRefused().containsKey(placementId);
            }
        };
    }

    /**
     * The {@code Where} of every enabled placement answering to {@code npcId} (alias-inclusive), in
     * placement order; empty when nothing places the character.
     */
    @Nonnull
    public static List<WorldSelector> wheresOf(@Nullable String npcId) {
        List<WorldSelector> out = new ArrayList<>();
        for (String placementId : NpcIdentities.placementsForNpcId(npcId)) {
            NpcPlacementAsset placement = NpcPlacementConfig.getInstance().resolve(placementId);
            if (placement != null && placement.isEnabled()) {
                out.add(effectiveWhere(placement));
            }
        }
        return out;
    }

    /**
     * Where the character answering to {@code npcId} was placed IN {@code worldName}, one position per
     * remembered instance; never another world's copy, nor another instance of the same dungeon.
     */
    @Nonnull
    public static List<WaypointPosition> positionsOf(@Nonnull String worldName, @Nullable String npcId) {
        List<WaypointPosition> out = new ArrayList<>();
        for (String placementId : NpcIdentities.placementsForNpcId(npcId)) {
            for (NpcPlacementPositionCache.Entry e : NpcPlacementPositionCache.forPlacement(worldName, placementId)) {
                out.add(new WaypointPosition(e.anchorKey(), e.x(), e.y(), e.z()));
            }
        }
        return out;
    }
}
