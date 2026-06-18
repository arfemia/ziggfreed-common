package com.ziggfreed.common.worldmap;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.protocol.packets.worldmap.MapMarker;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapManager;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MapMarkerBuilder;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MarkersCollector;
import com.ziggfreed.common.ZiggfreedCommonPlugin;

/**
 * A thin, reusable wrapper over Hytale's native {@code WorldMapManager} POI / marker
 * API for placing markers on the in-game world map + compass. Two flavors:
 *
 * <ul>
 *   <li><b>Global POI</b> ({@link #place}/{@link #remove}/{@link #clearAll}) - a marker
 *       written into {@code WorldMapManager.getPointsOfInterest()}, which the engine's
 *       built-in {@code POIMarkerProvider} broadcasts to EVERY player in the world. The
 *       backing map is a {@code ConcurrentHashMap}, so these ops are thread-safe.</li>
 *   <li><b>Per-player</b> ({@link #registerProvider}/{@link #unregisterProvider}) - a
 *       {@link PlayerMarkerProvider} the consumer implements to return the markers a
 *       GIVEN player should see (e.g. their own active-quest waypoints). Adapted onto
 *       the native per-player {@code MarkerProvider} and try-guarded.</li>
 * </ul>
 *
 * <p>Build a marker via {@link #marker} (hides the {@code MapMarkerBuilder} +
 * {@code Transform} plumbing). {@code icon} is a client map-marker texture id (e.g.
 * {@code "Portal.png"} / {@code "Home.png"}); {@code name} is an optional {@link Message}
 * shown on hover (a translation key resolves per-locale client-side). Every engine call
 * is try-guarded so a missing world / manager degrades to a no-op, never a throw.
 *
 * <p><b>Rendering precondition:</b> the {@code WorldMapManager} only delivers markers
 * while the world has its compass or world map enabled
 * ({@code World.isCompassUpdating() || isWorldMapEnabled()}); a bespoke instance world
 * with both off shows nothing until {@code World.setCompassUpdating(true)} is set.
 *
 * <p><b>Key collisions:</b> the engine pre-registers providers under {@code "poi"},
 * {@code "spawn"}, {@code "respawn"}, {@code "death"}, {@code "personal"},
 * {@code "shared"}, {@code "playerIcons"} - a consumer provider/marker id must avoid
 * those. Prefer a mod-prefixed key.
 */
public final class WorldMapMarkers {

    private WorldMapMarkers() {
    }

    /**
     * A per-player marker source: return the markers {@code player} should see this
     * update (empty for none). Build each via {@link WorldMapMarkers#marker}. Runs on
     * the world-map tracker thread; keep it allocation-light and exception-free (it is
     * try-guarded regardless).
     */
    @FunctionalInterface
    public interface PlayerMarkerProvider {
        @Nonnull
        List<MapMarker> markersFor(@Nonnull World world, @Nonnull Player player);
    }

    /**
     * Build a {@link MapMarker} at explicit world coordinates. The on-map position uses
     * only X/Z; Y is carried but not displayed on the minimap.
     *
     * @param id   unique marker id (used to place/remove it)
     * @param icon client map-marker texture id (e.g. {@code "Portal.png"})
     * @param name optional hover name ({@code null} = no name)
     */
    @Nonnull
    public static MapMarker marker(@Nonnull String id, @Nonnull String icon,
                                   double x, double y, double z, @Nullable Message name) {
        MapMarkerBuilder b = new MapMarkerBuilder(id, icon, new Transform(x, y, z));
        if (name != null) {
            b.withName(name);
        }
        return b.build();
    }

    // ---- global POI markers (world-wide, all players) ----

    /**
     * Place a world-wide POI marker at explicit coordinates. Overwrites any existing
     * marker with the same id.
     *
     * @return {@code true} if placed, {@code false} on failure (no manager / threw)
     */
    public static boolean place(@Nonnull World world, @Nonnull String id,
                                double x, double y, double z,
                                @Nonnull String icon, @Nullable Message name) {
        return place(world, marker(id, icon, x, y, z, name));
    }

    /**
     * Place a pre-built world-wide POI marker (keyed by its own {@code id}). Overwrites
     * any existing marker with the same id.
     *
     * @return {@code true} if placed, {@code false} on failure
     */
    public static boolean place(@Nonnull World world, @Nonnull MapMarker marker) {
        try {
            WorldMapManager manager = world.getWorldMapManager();
            if (manager == null) {
                return false;
            }
            manager.getPointsOfInterest().put(marker.id, marker);
            return true;
        } catch (Throwable t) {
            warn("place", marker.id, t);
            return false;
        }
    }

    /**
     * Remove a world-wide POI marker by id.
     *
     * @return {@code true} if a marker was removed, {@code false} if absent / on failure
     */
    public static boolean remove(@Nonnull World world, @Nonnull String id) {
        try {
            WorldMapManager manager = world.getWorldMapManager();
            if (manager == null) {
                return false;
            }
            return manager.getPointsOfInterest().remove(id) != null;
        } catch (Throwable t) {
            warn("remove", id, t);
            return false;
        }
    }

    /**
     * Clear every world-wide POI marker (does NOT touch the per-player providers or the
     * engine's built-in markers).
     *
     * @return {@code true} on success, {@code false} on failure
     */
    public static boolean clearAll(@Nonnull World world) {
        try {
            WorldMapManager manager = world.getWorldMapManager();
            if (manager == null) {
                return false;
            }
            manager.getPointsOfInterest().clear();
            return true;
        } catch (Throwable t) {
            warn("clearAll", "*", t);
            return false;
        }
    }

    // ---- per-player marker providers ----

    /**
     * Register a per-player marker provider (markers respect each player's map view
     * distance). Use a unique, mod-prefixed {@code key}; registering the same key again
     * replaces the prior provider.
     *
     * @return {@code true} if registered, {@code false} on failure
     */
    public static boolean registerProvider(@Nonnull World world, @Nonnull String key,
                                           @Nonnull PlayerMarkerProvider provider) {
        return registerProvider(world, key, false, provider);
    }

    /**
     * Register a per-player marker provider.
     *
     * @param ignoreViewDistance {@code true} to show the markers regardless of the map
     *                           view radius (like respawn-home markers); {@code false}
     *                           to only show those within view distance
     * @return {@code true} if registered, {@code false} on failure
     */
    public static boolean registerProvider(@Nonnull World world, @Nonnull String key,
                                           boolean ignoreViewDistance,
                                           @Nonnull PlayerMarkerProvider provider) {
        try {
            WorldMapManager manager = world.getWorldMapManager();
            if (manager == null) {
                return false;
            }
            manager.addMarkerProvider(key, (w, player, collector) -> {
                try {
                    List<MapMarker> markers = provider.markersFor(w, player);
                    if (markers == null || markers.isEmpty()) {
                        return;
                    }
                    for (MapMarker m : markers) {
                        if (m == null) {
                            continue;
                        }
                        addTo(collector, m, ignoreViewDistance);
                    }
                } catch (Throwable t) {
                    warn("provider:" + key, "update", t);
                }
            });
            return true;
        } catch (Throwable t) {
            warn("registerProvider", key, t);
            return false;
        }
    }

    /**
     * Remove a previously-registered per-player provider by key. Avoid the engine's
     * built-in keys (see the class doc).
     *
     * @return {@code true} if a provider was removed, {@code false} if absent / on failure
     */
    public static boolean unregisterProvider(@Nonnull World world, @Nonnull String key) {
        try {
            WorldMapManager manager = world.getWorldMapManager();
            if (manager == null) {
                return false;
            }
            return manager.getMarkerProviders().remove(key) != null;
        } catch (Throwable t) {
            warn("unregisterProvider", key, t);
            return false;
        }
    }

    private static void addTo(@Nonnull MarkersCollector collector, @Nonnull MapMarker marker,
                              boolean ignoreViewDistance) {
        if (ignoreViewDistance) {
            collector.addIgnoreViewDistance(marker);
        } else {
            collector.add(marker);
        }
    }

    private static void warn(@Nonnull String op, @Nonnull String id, @Nonnull Throwable t) {
        try {
            ZiggfreedCommonPlugin.LOGGER.atFine().log(
                    "[ZiggfreedCommon] WorldMapMarkers." + op + "(" + id + ") failed: " + t.getMessage());
        } catch (Throwable ignored) {
            // a log-manager-less unit JVM must not crash on the logging facade itself
        }
    }
}
