package com.ziggfreed.common.npc.placement.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3dc;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.codec.Vec3;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.npc.placement.anchor.AnchorPosition;
import com.ziggfreed.common.npc.placement.anchor.StructureAnchorIndex;
import com.ziggfreed.common.npc.placement.anchor.ZoneAnchorIndex;
import com.ziggfreed.common.npc.placement.asset.NpcPlacementAsset;
import com.ziggfreed.common.npc.placement.registry.AnchorResolverRegistry;
import com.ziggfreed.common.npc.placement.registry.PlacementFactorRegistry;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.util.SplitMix64;

/**
 * Turns a placement's authored {@code Anchor} groups into the concrete list of places its NPCs
 * should stand, and applies {@code Limits} to that list.
 *
 * <p><b>Several anchor groups are a UNION, not a choice.</b> Each authored group contributes its
 * own positions and each position becomes an independent placement instance, so "a guide at spawn
 * and one at every trade post" is one file rather than two placements with duplicated identity and
 * interaction. Groups contribute in a fixed DECLARATION ORDER - WorldSpawn, Coords, Structure,
 * Zone, Custom - which is what makes {@code OncePerWorld} deterministic: it keeps the first
 * position in that order, so which one survives never depends on chunk-load timing.
 *
 * <p>{@code MaxPerWorld} counts across the whole union rather than per group, because the number a
 * server owner cares about is how many of this NPC are in their world, not how many came from a
 * particular anchor.
 *
 * <p>The spawn chance is a DETERMINISTIC roll over {@code (world seed, placement id, anchor key)}.
 * Never a plain random: the same instance must make the same decision on every reload, or a chunk
 * reload re-rolls the world's population every time a player walks past. The chance VALUE it rolls
 * against comes from {@code Limits.ChanceFormula} when one is authored and from
 * {@code Limits.SpawnChance} otherwise (see {@link #resolveChance}); the roll itself is the same
 * either way.
 *
 * <p>The pure helpers here take plain values and are unit-testable; only {@link #resolve} needs a
 * live world.
 */
public final class PlacementAnchors {

    /**
     * A fixed uuid for the world spawn-point query. A spawn provider may key on a player, and an
     * anchor that moved with whoever triggered the sweep would not be an anchor at all.
     */
    private static final UUID ANCHOR_QUERY_UUID = new UUID(0L, 0L);

    private PlacementAnchors() {
    }

    // ==================== live resolution ====================

    /**
     * Every position {@code placement} resolves to in {@code world}, in declaration order, with
     * {@code OncePerWorld} and {@code SpawnChance} applied. {@code MaxPerWorld} is NOT applied
     * here (it needs the ledger's current count, which only the caller has).
     *
     * <p>World-thread only; fully guarded, so a failing anchor contributes nothing rather than
     * breaking the sweep.
     */
    @Nonnull
    public static List<AnchorPosition> resolve(@Nonnull World world, @Nonnull Store<EntityStore> store,
            @Nonnull NpcPlacementAsset placement) {
        NpcPlacementAsset.Anchor anchor = placement.getAnchor();
        if (anchor == null || anchor.isBlank()) {
            return List.of();
        }
        String placementId = placement.getId() == null ? "" : placement.getId();
        List<AnchorPosition> union = new ArrayList<>();

        // Declaration order is the contract; see the class javadoc.
        addWorldSpawn(union, world, store, anchor.getWorldSpawn());
        addCoords(union, anchor.getCoords());
        addStructures(union, world, anchor.getStructure());
        addZone(union, world, anchor.getZone());
        addCustom(union, world, store, placementId, anchor.getCustom());

        NpcPlacementAsset.Limits limits = placement.getLimits();
        boolean once = limits != null && limits.effectiveOncePerWorld();
        double chance = resolveChance(placement, world, store);

        List<AnchorPosition> collapsed = collapse(union, once);
        return applyChance(collapsed, worldSeed(world), placementId, chance);
    }

    /**
     * The chance each resolved position of {@code placement} is kept with: the authored
     * {@code Limits.ChanceFormula} when it says anything, else the plain
     * {@code Limits.SpawnChance}, else 1.
     *
     * <p>The formula is evaluated ONCE per call, against a context carrying the world, the store
     * and the placement id as its payload, and with NO subject entity - a placement is decided
     * before anything stands at the spot to ask about, so a factor that needs a subject cannot
     * answer and contributes 0, exactly as it would in any other formula. Only the chance VALUE
     * comes from here; the keep-or-skip roll itself stays the deterministic per-position one.
     *
     * <p>Guarded and side-effect free, so a throwing factor provider falls back to the authored
     * number rather than emptying the world.
     */
    public static double resolveChance(@Nonnull NpcPlacementAsset placement, @Nullable World world,
            @Nullable Store<EntityStore> store) {
        NpcPlacementAsset.Limits limits = placement.getLimits();
        if (limits == null) {
            return 1.0;
        }
        if (limits.hasChanceFormula()) {
            try {
                FactorContext ctx = FactorContext.builder()
                        .payload(placement.getId() == null ? "" : placement.getId())
                        .world(world)
                        .store(store)
                        .build();
                double value = limits.getChanceFormula().evaluate(PlacementFactorRegistry.registry(), ctx);
                if (Double.isFinite(value)) {
                    return value;
                }
            } catch (Throwable t) {
                SafeLog.fine("[placement] chance formula failed for '" + placement.getId() + "': " + t.getMessage());
            }
        }
        return limits.effectiveSpawnChance();
    }

    private static void addWorldSpawn(@Nonnull List<AnchorPosition> out, @Nonnull World world,
            @Nonnull Store<EntityStore> store, @Nullable NpcPlacementAsset.Anchor.WorldSpawn anchor) {
        if (anchor == null) {
            return;
        }
        try {
            var provider = world.getWorldConfig().getSpawnProvider();
            if (provider == null) {
                PlacementDiag.once(world, "worldspawn-provider-null",
                        "[placement] WorldSpawn anchor in '" + NpcPlacementService.worldName(world)
                                + "': the world's spawn provider is null (world init not settled,"
                                + " or no spawn configured) - the anchor resolves nothing until it"
                                + " appears");
                return;
            }
            Transform spawn = provider.getSpawnPoint(world, ANCHOR_QUERY_UUID);
            if (spawn == null || spawn.getPosition() == null) {
                PlacementDiag.once(world, "worldspawn-spawn-null",
                        "[placement] WorldSpawn anchor in '" + NpcPlacementService.worldName(world)
                                + "': provider " + provider.getClass().getSimpleName()
                                + " answered no spawn point - the anchor resolves nothing");
                return;
            }
            Vector3dc base = spawn.getPosition();
            Vec3 offset = anchor.getOffset();
            out.add(AnchorPosition.single(AnchorPosition.AnchorKind.WORLD_SPAWN,
                    base.x() + offsetX(offset), base.y() + offsetY(offset), base.z() + offsetZ(offset),
                    anchor.effectiveYaw()));
        } catch (Throwable t) {
            PlacementDiag.once(world, "worldspawn-throw",
                    "[placement] WorldSpawn anchor failed in '" + NpcPlacementService.worldName(world)
                            + "': " + t);
        }
    }

    private static void addCoords(@Nonnull List<AnchorPosition> out,
            @Nullable NpcPlacementAsset.Anchor.Coords anchor) {
        if (anchor == null || !anchor.isComplete()) {
            return;
        }
        out.add(AnchorPosition.single(AnchorPosition.AnchorKind.COORDS,
                anchor.getX(), anchor.getY(), anchor.getZ(), anchor.effectiveYaw()));
    }

    private static void addStructures(@Nonnull List<AnchorPosition> out, @Nonnull World world,
            @Nullable NpcPlacementAsset.Anchor.Structure anchor) {
        if (anchor == null || anchor.hasNoMatcher()) {
            return;
        }
        Vec3 offset = anchor.getOffset();
        for (StructureAnchorIndex.Marker marker
                : StructureAnchorIndex.matching(StructureAnchorIndex.markersIn(world), anchor)) {
            out.add(new AnchorPosition(AnchorPosition.AnchorKind.STRUCTURE,
                    marker.instanceId(),
                    marker.x() + offsetX(offset), marker.y() + offsetY(offset), marker.z() + offsetZ(offset),
                    anchor.effectiveYaw()));
        }
    }

    private static void addZone(@Nonnull List<AnchorPosition> out, @Nonnull World world,
            @Nullable NpcPlacementAsset.Anchor.Zone anchor) {
        if (anchor == null || anchor.isBlank()) {
            return;
        }
        ZoneAnchorIndex.Discovery discovery = ZoneAnchorIndex.discoveryOf(world, anchor.getZone());
        if (discovery == null) {
            return;
        }
        Vec3 offset = anchor.getOffset();
        out.add(new AnchorPosition(AnchorPosition.AnchorKind.ZONE, discovery.zoneName(),
                discovery.x() + offsetX(offset), discovery.y() + offsetY(offset), discovery.z() + offsetZ(offset),
                anchor.effectiveYaw()));
    }

    private static void addCustom(@Nonnull List<AnchorPosition> out, @Nonnull World world,
            @Nonnull Store<EntityStore> store, @Nonnull String placementId,
            @Nullable NpcPlacementAsset.Anchor.Custom anchor) {
        if (anchor == null || anchor.isBlank()) {
            return;
        }
        out.addAll(AnchorResolverRegistry.resolve(anchor.getProvider(),
                new AnchorResolverRegistry.AnchorRequest(placementId, world, store, anchor.getParams())));
    }

    // ==================== pure limit helpers ====================

    /**
     * Apply {@code OncePerWorld}: keep only the first position in declaration order. PURE.
     *
     * <p>Collapsing to the FIRST rather than to a "best" position is deliberate: the author
     * controls the order by which anchor groups they write, so the outcome is readable off the
     * file instead of depending on which structure a player happened to load first.
     */
    @Nonnull
    public static List<AnchorPosition> collapse(@Nonnull List<AnchorPosition> union, boolean oncePerWorld) {
        if (!oncePerWorld || union.size() <= 1) {
            return List.copyOf(union);
        }
        return List.of(union.get(0));
    }

    /**
     * Apply {@code SpawnChance}: keep each position whose deterministic roll passes. PURE.
     *
     * <p>The roll folds the world seed with the placement id and the anchor key, so it is stable
     * across reloads and restarts and independent per instance. A chance at or above 1 keeps
     * everything without rolling; a chance at or below 0 keeps nothing.
     */
    @Nonnull
    public static List<AnchorPosition> applyChance(@Nonnull List<AnchorPosition> positions, long worldSeed,
            @Nonnull String placementId, double chance) {
        if (chance >= 1.0) {
            return List.copyOf(positions);
        }
        if (chance <= 0.0) {
            return List.of();
        }
        List<AnchorPosition> out = new ArrayList<>(positions.size());
        for (AnchorPosition position : positions) {
            if (roll(worldSeed, placementId, position.anchorKey()) < chance) {
                out.add(position);
            }
        }
        return out;
    }

    /**
     * Apply {@code MaxPerWorld} across the whole union: keep at most {@code max - alreadyPlaced}
     * more positions. PURE. A non-positive {@code max} means unlimited.
     */
    @Nonnull
    public static List<AnchorPosition> applyMaxPerWorld(@Nonnull List<AnchorPosition> positions, int max,
            int alreadyPlaced) {
        if (max <= 0) {
            return List.copyOf(positions);
        }
        int remaining = max - alreadyPlaced;
        if (remaining <= 0) {
            return List.of();
        }
        if (positions.size() <= remaining) {
            return List.copyOf(positions);
        }
        return List.copyOf(positions.subList(0, remaining));
    }

    /**
     * The deterministic roll in {@code [0, 1)} for one placement instance. PURE, and never
     * {@code java.util.Random}: identical inputs must give the identical decision forever, or a
     * chunk reload re-rolls a world's population.
     */
    public static double roll(long worldSeed, @Nonnull String placementId, @Nonnull String anchorKey) {
        long folded = SplitMix64.mix(worldSeed, ((long) placementId.hashCode() << 32) ^ anchorKey.hashCode());
        return new SplitMix64(folded).nextDouble();
    }

    // ==================== helpers ====================

    private static double offsetX(@Nullable Vec3 offset) {
        return offset == null ? 0.0 : offset.effectiveX();
    }

    private static double offsetY(@Nullable Vec3 offset) {
        return offset == null ? 0.0 : offset.effectiveY();
    }

    private static double offsetZ(@Nullable Vec3 offset) {
        return offset == null ? 0.0 : offset.effectiveZ();
    }

    private static long worldSeed(@Nonnull World world) {
        try {
            return world.getWorldConfig().getSeed();
        } catch (Throwable t) {
            return 0L;
        }
    }
}
