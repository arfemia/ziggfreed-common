package com.ziggfreed.common.worldmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.protocol.packets.worldmap.MapMarker;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;

/**
 * A reusable "discoverable points of interest" tracker over {@link WorldMapMarkers}: a set of POIs that
 * stay HIDDEN on the world map / compass until DISCOVERED, then surface as markers. Discovery has two
 * orthogonal axes the consumer chooses as policy:
 *
 * <ul>
 *   <li><b>Trigger</b> ({@link DiscoveryMode}) - HOW a POI is revealed. {@link #discover} is the
 *       {@code ON_INTERACT} entry; {@link #revealWithin} is the {@code PROXIMITY} entry (the consumer
 *       calls it from its own tick). {@code OFF} means the consumer never builds a tracker at all.</li>
 *   <li><b>Visibility</b> ({@link Visibility}, per-POI) - WHO sees a discovered POI: only the discovering
 *       player ({@link Visibility#PER_PLAYER}) or every player once anyone discovers it
 *       ({@link Visibility#SHARED}). One tracker can host both kinds at once.</li>
 * </ul>
 *
 * <p>The consumer holds one instance per context (e.g. per game round), {@link #register}s its POIs
 * (lazily on interact, or all up front for proximity), {@link #attach}es it to the world (which enables
 * the compass and registers ONE per-player marker provider), and {@link #detach}es it when the context
 * ends. {@link #discover} returns {@code true} only on the FIRST discovery of a (POI, viewer) pair, so a
 * consumer can fire a one-time cue (sound / toast) exactly once.
 *
 * <p>This is a generic engine wrapper: it holds no consumer types, no baked ids / icons, and takes
 * display {@link Message}s from the consumer. Like the rest of {@code worldmap/}, every engine call is
 * try-guarded inside {@link WorldMapMarkers}, so a missing world / manager degrades to a no-op.
 *
 * <p><b>Threading.</b> {@link #register}/{@link #discover}/{@link #updateIcon}/{@link #unregister}/
 * {@link #attach}/{@link #detach}/{@link #revealWithin} are consumer-world-thread calls (they mutate the
 * tracker or touch the {@code WorldMapManager}). The per-player provider's {@code markersFor} runs on the
 * world-map tracker thread and only READS the tracker's {@link ConcurrentHashMap}s plus the passed
 * {@link Player} - it never touches consumer state, so no lock is needed. Keep a tracker free of consumer
 * objects to preserve that boundary.
 *
 * <p><b>Provider key.</b> Each tracker owns one mod-prefixed provider key (avoid the engine-reserved
 * {@code poi/spawn/respawn/death/personal/shared/playerIcons}); registering the same key twice replaces
 * the prior provider (see {@link WorldMapMarkers#registerProvider}).
 */
public final class MapDiscovery {

    /** WHO sees a discovered POI. */
    public enum Visibility {
        /** Only the player who discovered it. */
        PER_PLAYER,
        /** Every player, once any one player discovers it. */
        SHARED
    }

    /**
     * A point of interest that renders as a map marker once discovered. Immutable; re-{@link #register}ing
     * the same id replaces the definition (icon / position / name / visibility) while KEEPING who has
     * already discovered it.
     *
     * @param id         unique marker id (also the discovery key)
     * @param icon       client map-marker texture id (e.g. {@code "Temple_Gateway.png"})
     * @param name       optional hover name ({@code null} = no name); a client-resolved {@link Message}
     * @param visibility who sees it once discovered
     */
    public record DiscoverablePoi(@Nonnull String id, @Nonnull String icon,
                                  double x, double y, double z,
                                  @Nullable Message name, @Nonnull Visibility visibility) {
    }

    /** A newly-revealed (POI, player) pair returned from {@link #revealWithin}. */
    public record Discovery(@Nonnull String poiId, @Nonnull UUID playerUuid) {
    }

    private final String providerKey;
    private final boolean ignoreViewDistance;

    /** All known POIs, keyed by id. Re-register replaces; the discovery sets below survive it. */
    private final Map<String, DiscoverablePoi> pois = new ConcurrentHashMap<>();
    /** Ids of {@link Visibility#SHARED} POIs that have been discovered (visible to everyone). */
    private final Set<String> sharedRevealed = ConcurrentHashMap.newKeySet();
    /** Per-player reveals: POI id -> the player uuids who have discovered it ({@link Visibility#PER_PLAYER}). */
    private final Map<String, Set<UUID>> playerRevealed = new ConcurrentHashMap<>();

    /** Create a tracker whose markers respect each player's map view distance. */
    public MapDiscovery(@Nonnull String providerKey) {
        this(providerKey, false);
    }

    /**
     * @param ignoreViewDistance {@code true} to show discovered markers regardless of the map view radius
     *                           (like respawn-home markers); {@code false} to only show those within it
     */
    public MapDiscovery(@Nonnull String providerKey, boolean ignoreViewDistance) {
        this.providerKey = providerKey;
        this.ignoreViewDistance = ignoreViewDistance;
    }

    // ---- registration / mutation (consumer world thread) ----

    /** Register (or replace) a POI; keeps any existing discovery state for its id. Returns {@code this}. */
    @Nonnull
    public MapDiscovery register(@Nonnull DiscoverablePoi poi) {
        pois.put(poi.id(), poi);
        return this;
    }

    /** Convenience {@link #register} that builds the {@link DiscoverablePoi} from fields. */
    @Nonnull
    public MapDiscovery register(@Nonnull String id, @Nonnull String icon,
                                 double x, double y, double z,
                                 @Nullable Message name, @Nonnull Visibility visibility) {
        return register(new DiscoverablePoi(id, icon, x, y, z, name, visibility));
    }

    /** Swap a registered POI's icon in place (e.g. an objective completing). No-op if the id is unknown. */
    public void updateIcon(@Nonnull String id, @Nonnull String icon) {
        pois.computeIfPresent(id, (k, cur) ->
                new DiscoverablePoi(id, icon, cur.x(), cur.y(), cur.z(), cur.name(), cur.visibility()));
    }

    /** Remove a POI and forget who discovered it. */
    public void unregister(@Nonnull String id) {
        pois.remove(id);
        sharedRevealed.remove(id);
        playerRevealed.remove(id);
    }

    // ---- discovery (consumer world thread) ----

    /**
     * Record that {@code playerUuid} discovered the POI {@code poiId}. Routes by the POI's
     * {@link Visibility}: a {@link Visibility#SHARED} POI is revealed for everyone, a
     * {@link Visibility#PER_PLAYER} POI only for that player.
     *
     * @return {@code true} only on the FIRST discovery of this (POI, viewer); {@code false} if already
     *         discovered or the id is not registered
     */
    public boolean discover(@Nonnull String poiId, @Nonnull UUID playerUuid) {
        DiscoverablePoi poi = pois.get(poiId);
        if (poi == null) {
            return false;
        }
        if (poi.visibility() == Visibility.SHARED) {
            return sharedRevealed.add(poiId);
        }
        return playerRevealed.computeIfAbsent(poiId, k -> ConcurrentHashMap.newKeySet()).add(playerUuid);
    }

    /** Whether {@code viewer} can currently see the POI {@code poiId} (shared-revealed, or revealed to them). */
    public boolean isDiscovered(@Nonnull String poiId, @Nonnull UUID viewer) {
        if (sharedRevealed.contains(poiId)) {
            return true;
        }
        Set<UUID> set = playerRevealed.get(poiId);
        return set != null && set.contains(viewer);
    }

    /**
     * PURE proximity sweep (the {@code PROXIMITY} trigger): reveal every registered POI within
     * {@code radius} blocks of a player's position. Pass each player's current position; the method reads
     * NO world / store, so the consumer gathers positions on its own tick and calls this.
     *
     * @return the (POI, player) pairs revealed by THIS call (each already recorded via {@link #discover}),
     *         so the consumer can fire a one-time cue per new discovery
     */
    @Nonnull
    public List<Discovery> revealWithin(@Nonnull Map<UUID, Vector3d> positions, double radius) {
        List<Discovery> out = new ArrayList<>();
        if (positions.isEmpty() || pois.isEmpty() || radius <= 0.0) {
            return out;
        }
        double r2 = radius * radius;
        for (Map.Entry<UUID, Vector3d> e : positions.entrySet()) {
            UUID uuid = e.getKey();
            Vector3d p = e.getValue();
            if (uuid == null || p == null) {
                continue;
            }
            for (DiscoverablePoi poi : pois.values()) {
                if (isDiscovered(poi.id(), uuid)) {
                    continue;
                }
                double dx = p.x - poi.x();
                double dy = p.y - poi.y();
                double dz = p.z - poi.z();
                if (dx * dx + dy * dy + dz * dz <= r2 && discover(poi.id(), uuid)) {
                    out.add(new Discovery(poi.id(), uuid));
                }
            }
        }
        return out;
    }

    // ---- world attach / detach (consumer world thread) ----

    /**
     * Enable the world's compass (the marker rendering precondition) and register this tracker's
     * per-player marker provider under its key. Call once when the context starts, before any discovery.
     */
    public void attach(@Nonnull World world) {
        world.setCompassUpdating(true);
        WorldMapMarkers.registerProvider(world, providerKey, ignoreViewDistance, (w, player) -> markersFor(player));
    }

    /**
     * Remove this tracker's marker provider. Leaves the compass enabled (other markers may still want it).
     * Defensive when the world is about to be destroyed; required if the world outlives the tracker.
     */
    public void detach(@Nonnull World world) {
        WorldMapMarkers.unregisterProvider(world, providerKey);
    }

    /** The per-player marker source (world-map tracker thread): the POIs {@code player} has discovered. */
    @Nonnull
    @SuppressWarnings("removal") // Entity.getUuid() is the only uuid accessor from a bare Player (no store here); the engine's own codec uses it.
    private List<MapMarker> markersFor(@Nonnull Player player) {
        UUID viewer = player.getUuid();
        if (viewer == null || pois.isEmpty()) {
            return List.of();
        }
        List<MapMarker> out = new ArrayList<>();
        for (DiscoverablePoi poi : pois.values()) {
            if (isDiscovered(poi.id(), viewer)) {
                out.add(WorldMapMarkers.marker(poi.id(), poi.icon(), poi.x(), poi.y(), poi.z(), poi.name()));
            }
        }
        return out;
    }
}
