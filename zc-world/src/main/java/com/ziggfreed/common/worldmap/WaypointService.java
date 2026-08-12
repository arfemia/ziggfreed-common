package com.ziggfreed.common.worldmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.protocol.packets.worldmap.MapMarker;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.ziggfreed.common.util.SafeLog;

/**
 * Points each viewer at wherever they are currently being pointed, as markers on the world map and
 * compass. The reusable half of "show me where to go next": a consumer supplies WHY somebody is
 * being pointed somewhere ({@link WaypointTargetSource}) and WHERE a named place actually is
 * ({@link WaypointPositionResolver}); everything between is here.
 *
 * <p>Wiring one up is three calls:
 * <pre>{@code
 * WaypointService waypoints = WaypointService.builder("yourmod:waypoints")
 *         .defaultIcon("Coordinate.png")
 *         .positionResolver(myPositionLookup)
 *         .build();
 * waypoints.addSource(mySource);
 * waypoints.registerForWorld(world);   // once per world, idempotent
 * }</pre>
 * and then {@code waypoints.refresh(viewerId)} whenever what a viewer is being pointed at may have
 * changed, plus {@code waypoints.clear(viewerId)} when they leave.
 *
 * <p><b>Multi-world.</b> Hytale runs many worlds at once, so a snapshot is world-INDEPENDENT (just
 * the places, by key) and the provider is registered once PER WORLD; the resolver is asked per
 * world, so a place that is not in this world contributes no marker here. Two live copies of one
 * place in the same world stay two markers, because the position's anchor key is part of the marker
 * id.
 *
 * <p><b>Threading.</b> {@link #refresh} runs on the consumer's own thread - the world thread, where
 * reading its state is legal. The marker callback runs OFF it, on the map tracker, and only reads
 * the concurrent snapshot map plus whatever the resolver reads. Nothing in the callback path touches
 * the entity store. Viewer identity is resolved for you by {@link WorldMapMarkers}, which is the one
 * place a bare {@link Player} is turned into a uuid.
 *
 * <p><b>Removal.</b> A marker disappears when its target leaves the viewer's snapshot (the next
 * refresh drops it) or when the resolver stops resolving the place. Nothing has to be un-drawn.
 */
public final class WaypointService {

    /** Client map-marker texture id used when neither the target nor the builder names one. */
    public static final String DEFAULT_ICON = "Coordinate.png";

    private final String providerKey;
    private final boolean ignoreViewDistance;
    private final boolean forceCompass;
    private final WaypointPositionResolver resolver;
    private final WaypointSnapshots snapshots;

    /** Worlds whose provider is already registered - register exactly once per world. */
    private final Set<String> registeredWorlds = ConcurrentHashMap.newKeySet();

    private WaypointService(@Nonnull Builder b) {
        this.providerKey = b.providerKey;
        this.ignoreViewDistance = b.ignoreViewDistance;
        this.forceCompass = b.forceCompass;
        this.resolver = b.resolver;
        this.snapshots = new WaypointSnapshots(b.providerKey, b.defaultIcon, b.warn);
    }

    /**
     * @param providerKey a mod-prefixed key, unique per consumer. It names the marker provider AND
     *                    prefixes every marker id. Avoid the engine's own reserved provider keys -
     *                    see {@link WorldMapMarkers}.
     */
    @Nonnull
    public static Builder builder(@Nonnull String providerKey) {
        return new Builder(providerKey);
    }

    /** The snapshot core, for a consumer that wants the pure rule without the world binding. */
    @Nonnull
    public WaypointSnapshots snapshots() {
        return snapshots;
    }

    /** The provider key this service registers under and prefixes its marker ids with. */
    @Nonnull
    public String providerKey() {
        return providerKey;
    }

    // ==================== Sources and snapshots ====================

    /** Add a reason viewers get pointed somewhere. Registering the same instance twice is a no-op. */
    public void addSource(@Nonnull WaypointTargetSource source) {
        snapshots.addSource(source);
    }

    /** Drop a source. Returns true when it was registered. */
    public boolean removeSource(@Nonnull WaypointTargetSource source) {
        return snapshots.removeSource(source);
    }

    /**
     * Rebuild one viewer's snapshot from every source. Call it whenever what they are being pointed
     * at may have changed; it is cheap and idempotent, so an over-eager call costs nothing.
     *
     * @return how many places the viewer is now being pointed at
     */
    public int refresh(@Nonnull UUID viewerId) {
        return snapshots.refresh(viewerId);
    }

    /** Replace a viewer's snapshot outright, without asking the sources. */
    public int set(@Nonnull UUID viewerId, @Nullable List<WaypointTarget> targets) {
        return snapshots.set(viewerId, targets);
    }

    /** Forget a viewer's snapshot (they disconnected). */
    public void clear(@Nonnull UUID viewerId) {
        snapshots.clear(viewerId);
    }

    // ==================== World binding ====================

    /**
     * Register the per-player marker provider for {@code world}. Idempotent and multi-world safe, so
     * calling it every time a player becomes ready is the intended usage.
     *
     * @return true when this call registered it (false when it was already registered, or failed)
     */
    public boolean registerForWorld(@Nonnull World world) {
        try {
            if (!registeredWorlds.add(world.getName())) {
                return false;
            }
            if (forceCompass) {
                // Markers only deliver while the world's compass or map is on: a no-op on an ordinary
                // world, and the difference between markers and nothing on a bespoke one.
                world.setCompassUpdating(true);
            }
            return WorldMapMarkers.registerProvider(world, providerKey, ignoreViewDistance,
                    (w, player, viewerId) -> markersFor(w.getName(), viewerId));
        } catch (Throwable t) {
            SafeLog.warn("[waypoint] provider '" + providerKey + "' could not be registered for world '"
                    + world.getName() + "': " + t.getMessage());
            return false;
        }
    }

    /** Stop providing markers in {@code world}. */
    public boolean unregisterForWorld(@Nonnull World world) {
        registeredWorlds.remove(world.getName());
        return WorldMapMarkers.unregisterProvider(world, providerKey);
    }

    /** This viewer's markers in this world, built from the snapshot. Runs on the map tracker. */
    @Nonnull
    private List<MapMarker> markersFor(@Nonnull String worldName, @Nonnull UUID viewerId) {
        List<WaypointSnapshots.MarkerSpec> specs = snapshots.markerSpecsFor(worldName, viewerId, resolver);
        if (specs.isEmpty()) {
            return List.of();
        }
        List<MapMarker> out = new ArrayList<>(specs.size());
        for (WaypointSnapshots.MarkerSpec spec : specs) {
            out.add(WorldMapMarkers.marker(spec.id(), spec.icon(), spec.x(), spec.y(), spec.z(),
                    spec.title()));
        }
        return out;
    }

    // ==================== Builder ====================

    /** Assembles a {@link WaypointService}; only the provider key is required. */
    public static final class Builder {

        private final String providerKey;
        private String defaultIcon = DEFAULT_ICON;
        private boolean ignoreViewDistance = true;
        private boolean forceCompass = true;
        private WaypointPositionResolver resolver = WaypointPositionResolver.NONE;
        private Consumer<String> warn = DEFAULT_WARN;

        private Builder(@Nonnull String providerKey) {
            this.providerKey = providerKey;
        }

        /** The texture id a target that names none of its own takes. */
        @Nonnull
        public Builder defaultIcon(@Nonnull String defaultIcon) {
            this.defaultIcon = defaultIcon;
            return this;
        }

        /**
         * How a named place is turned into coordinates. Left unset, nothing ever resolves and no
         * marker is ever drawn.
         */
        @Nonnull
        public Builder positionResolver(@Nonnull WaypointPositionResolver resolver) {
            this.resolver = resolver;
            return this;
        }

        /**
         * Whether markers render regardless of the map's view radius. ON by default, because the
         * point of a waypoint is usually somewhere the viewer cannot see yet; turn it off for
         * markers that are only interesting nearby.
         */
        @Nonnull
        public Builder ignoreViewDistance(boolean ignoreViewDistance) {
            this.ignoreViewDistance = ignoreViewDistance;
            return this;
        }

        /**
         * Whether registering switches the world's compass on. ON by default so a bespoke world with
         * the compass and map both off still shows markers; turn it off to leave that as the world's
         * own business.
         */
        @Nonnull
        public Builder forceCompass(boolean forceCompass) {
            this.forceCompass = forceCompass;
            return this;
        }

        /** Where a misbehaving source or resolver is reported. Defaults to the library logger. */
        @Nonnull
        public Builder warn(@Nonnull Consumer<String> warn) {
            this.warn = warn;
            return this;
        }

        @Nonnull
        public WaypointService build() {
            return new WaypointService(this);
        }
    }

    /** Default warn sink: the library logger, guarded so a log-manager-less test JVM cannot crash. */
    private static final Consumer<String> DEFAULT_WARN = message -> SafeLog.warn("[waypoint] " + message);
}
