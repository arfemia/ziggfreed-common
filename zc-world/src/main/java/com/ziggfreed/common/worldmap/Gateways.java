package com.ziggfreed.common.worldmap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.meta.state.BlockMapMarker;
import com.hypixel.hytale.server.core.universe.world.meta.state.BlockMapMarkersResource;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.ziggfreed.common.cast.WorldEvictors;
import com.ziggfreed.common.codec.Vec3;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.world.WorldSelector;

/**
 * Where each world's gateways stand, and which one a viewer is pointed at.
 *
 * <p>{@link #index(World)} runs on the world thread: it reads the engine's record of every block map
 * marker in the world ({@code BlockMapMarkersResource}, filled as chunks generate and kept across
 * unloads) and keeps each gateway's positions in a concurrent table. {@link #positions} and
 * {@link #nearest} are what the map tracker reads, so nothing on that thread touches a store.
 */
public final class Gateways {

    /** One block map marker the engine recorded: its cell and its name. */
    public record BlockMarker(int x, int y, int z, @Nonnull String name) {
    }

    /** World name, lower case, to gateway id, lower case, to its positions there, as last indexed. */
    private static final Map<String, Map<String, List<WaypointPosition>>> INDEX = new ConcurrentHashMap<>();

    /** Block ids already reported as drawing no map marker, so the log says so once per id. */
    private static final Set<String> MARKERLESS_REPORTED = ConcurrentHashMap.newKeySet();

    static {
        // A removed world takes what was indexed of it along, so the index never outlives its worlds;
        // a world that comes back under the same name is indexed afresh on its next use.
        WorldEvictors.registerEvictor(world -> forget(world.getName()));
    }

    private Gateways() {
    }

    /**
     * Every gateway this server knows: the base game's portals (read once, {@link PortalGateways}) under
     * every pack's and the owner's files. Touches the engine's block table; never from a unit test.
     */
    @Nonnull
    public static Collection<GatewayAsset> all() {
        PortalGateways.ensureDerived();
        return GatewayConfig.getInstance().all().values();
    }

    /**
     * Every enabled gateway standing in this world that leads into a world one of {@code targetWheres}
     * matches, ordered by id.
     */
    @Nonnull
    public static List<GatewayAsset> leadingInto(@Nonnull Collection<GatewayAsset> gateways,
            @Nonnull Collection<WorldSelector> targetWheres, @Nullable String worldName,
            @Nullable String worldGameplayConfig) {
        List<GatewayAsset> out = new ArrayList<>();
        for (GatewayAsset gateway : gateways) {
            if (gateway != null && gateway.getId() != null && gateway.isEnabled()
                    && gateway.standsIn(worldName, worldGameplayConfig) && gateway.leadsInto(targetWheres)) {
                out.add(gateway);
            }
        }
        out.sort(Comparator.comparing(g -> g.getId().toLowerCase(Locale.ROOT)));
        return out;
    }

    /**
     * Each gateway's ways in among {@code markers} (the cells whose marker name one of its blocks
     * draws, block-centred) plus its fixed positions, keyed by lower-case id. A gateway with none is
     * left out. Pure: the block-to-marker-name lookup is handed in.
     */
    @Nonnull
    public static Map<String, List<WaypointPosition>> positionsByGateway(@Nonnull Collection<GatewayAsset> gateways,
            @Nonnull Function<String, String> markerNameOfBlock, @Nonnull Collection<BlockMarker> markers) {
        Map<String, List<WaypointPosition>> out = new LinkedHashMap<>();
        for (GatewayAsset gateway : gateways) {
            if (gateway == null || gateway.getId() == null || !gateway.isEnabled()) {
                continue;
            }
            Set<String> names = new HashSet<>();
            for (String block : gateway.blockIds()) {
                String name = markerNameOfBlock.apply(block);
                if (name != null && !name.isBlank()) {
                    names.add(name);
                }
            }
            List<WaypointPosition> ways = new ArrayList<>();
            if (!names.isEmpty()) {
                for (BlockMarker marker : markers) {
                    if (names.contains(marker.name())) {
                        ways.add(new WaypointPosition(marker.x() + "," + marker.y() + "," + marker.z(),
                                marker.x() + 0.5, marker.y(), marker.z() + 0.5));
                    }
                }
            }
            List<Vec3> fixed = gateway.fixedPositions();
            for (int i = 0; i < fixed.size(); i++) {
                Vec3 at = fixed.get(i);
                ways.add(new WaypointPosition("at" + i, at.effectiveX(), at.effectiveY(), at.effectiveZ()));
            }
            if (!ways.isEmpty()) {
                out.put(gateway.getId().toLowerCase(Locale.ROOT), List.copyOf(ways));
            }
        }
        return out;
    }

    /**
     * The way in nearest {@code viewer} on the map (X and Z), as a one-element list; every way in when
     * the viewer's position is unknown.
     */
    @Nonnull
    public static List<WaypointPosition> nearest(@Nonnull List<WaypointPosition> ways, @Nullable WaypointViewer viewer) {
        if (viewer == null || ways.size() <= 1) {
            return ways;
        }
        WaypointPosition best = null;
        double bestSq = Double.MAX_VALUE;
        for (WaypointPosition way : ways) {
            double dx = way.x() - viewer.x();
            double dz = way.z() - viewer.z();
            double sq = dx * dx + dz * dz;
            if (sq < bestSq) {
                bestSq = sq;
                best = way;
            }
        }
        return best == null ? List.of() : List.of(best);
    }

    /**
     * World thread: record where every enabled gateway standing in {@code world} has a way in, from
     * the engine's block map markers and the gateways' fixed positions. One pass over the world's
     * markers (a read of the chunk store's resource, never a write); a world with no gateway standing
     * in it is forgotten.
     */
    public static void index(@Nonnull World world) {
        String worldName = world.getName();
        try {
            String gameplayConfig = world.getWorldConfig().getGameplayConfig();
            List<GatewayAsset> standingHere = new ArrayList<>();
            boolean anyBlocks = false;
            for (GatewayAsset gateway : all()) {
                if (gateway.isEnabled() && gateway.standsIn(worldName, gameplayConfig)) {
                    standingHere.add(gateway);
                    anyBlocks |= !gateway.blockIds().isEmpty();
                }
            }
            if (standingHere.isEmpty()) {
                forget(worldName);
                return;
            }
            List<BlockMarker> markers = new ArrayList<>();
            if (anyBlocks) {
                BlockMapMarkersResource.of(world).getMarkers().forEach((x, y, z, data) -> {
                    if (data != null && data.getName() != null) {
                        markers.add(new BlockMarker(x, y, z, data.getName()));
                    }
                });
            }
            remember(worldName, positionsByGateway(standingHere, Gateways::markerNameOfBlock, markers));
        } catch (Throwable t) {
            SafeLog.warn("[gateway] could not read where the gateways stand in world '" + worldName + "': "
                    + t.getMessage());
        }
    }

    /** Replace what is known of {@code worldName}'s gateways; {@link #index} writes through this. */
    public static void remember(@Nonnull String worldName, @Nonnull Map<String, List<WaypointPosition>> byGateway) {
        if (byGateway.isEmpty()) {
            forget(worldName);
            return;
        }
        Map<String, List<WaypointPosition>> copy = new LinkedHashMap<>();
        byGateway.forEach((id, ways) -> copy.put(id.toLowerCase(Locale.ROOT), List.copyOf(ways)));
        INDEX.put(worldName.toLowerCase(Locale.ROOT), Map.copyOf(copy));
    }

    /** Map-tracker safe: {@code gatewayId}'s ways in within {@code worldName}, as last indexed. */
    @Nonnull
    public static List<WaypointPosition> positions(@Nonnull String worldName, @Nonnull String gatewayId) {
        Map<String, List<WaypointPosition>> here = INDEX.get(worldName.toLowerCase(Locale.ROOT));
        return here == null ? List.of() : here.getOrDefault(gatewayId.toLowerCase(Locale.ROOT), List.of());
    }

    /** Forget a world (it was removed: wired to {@link WorldEvictors}, so no caller need do it). */
    public static void forget(@Nonnull String worldName) {
        INDEX.remove(worldName.toLowerCase(Locale.ROOT));
    }

    /** The name of the map marker {@code type} draws, or null when it draws none. Shared with {@link PortalGateways}. */
    @Nullable
    static String markerNameOf(@Nullable BlockType type) {
        Holder<ChunkStore> entity = type == null ? null : type.getBlockEntity();
        BlockMapMarker marker = entity == null ? null : entity.getComponent(BlockMapMarker.getComponentType());
        String name = marker == null ? null : marker.getName();
        return name == null || name.isBlank() ? null : name;
    }

    /** The name of the map marker {@code blockId} draws, or null; says once per id when it draws none. */
    @Nullable
    static String markerNameOfBlock(@Nonnull String blockId) {
        String name = markerNameOf(BlockType.getAssetMap().getAsset(blockId));
        if (name == null && MARKERLESS_REPORTED.add(blockId.toLowerCase(Locale.ROOT))) {
            SafeLog.warn("[gateway] block '" + blockId + "' draws no map marker, so the server cannot find its"
                    + " copies and a gateway naming it finds nothing there; author Positions instead");
        }
        return name;
    }
}
