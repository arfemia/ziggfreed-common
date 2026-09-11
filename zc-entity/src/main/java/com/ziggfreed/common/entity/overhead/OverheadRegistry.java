package com.ziggfreed.common.entity.overhead;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * One world's overhead indicators: every host with something shown over it, and every marker
 * entity standing in for a state, indexed both ways so the visibility filter can walk the few
 * markers and the follow pass can walk the hosts.
 *
 * <p>Keyed by {@link World} so an instance world that unloads drops its whole table in one
 * eviction, through the same fan-out every other per-world partition in the library registers
 * against.
 */
final class OverheadRegistry {

    /** One marker entity: which host it floats over, that host's record, and the state it stands for. */
    record Marker(@Nonnull Ref<EntityStore> hostRef, @Nonnull HostIndicators host, @Nonnull String stateKey,
                  long createdMs) {
    }

    private static final Map<World, OverheadRegistry> BY_WORLD = new ConcurrentHashMap<>();

    private final Map<Ref<EntityStore>, HostIndicators> hosts = new ConcurrentHashMap<>();

    private final Map<Ref<EntityStore>, Marker> markers = new ConcurrentHashMap<>();

    /** When the follow pass next runs for this world; the pass sets it as it goes. */
    volatile long nextFollowAtMs;

    private OverheadRegistry() {
    }

    /** This world's table, created on first use. */
    @Nonnull
    static OverheadRegistry of(@Nonnull World world) {
        return BY_WORLD.computeIfAbsent(world, w -> new OverheadRegistry());
    }

    /** This world's table, or null when nothing has ever been shown here. */
    @Nullable
    static OverheadRegistry peek(@Nonnull World world) {
        return BY_WORLD.get(world);
    }

    /** Every world's table, for a sweep that touches all of them (a viewer leaving, a shutdown). */
    @Nonnull
    static Collection<OverheadRegistry> all() {
        return BY_WORLD.values();
    }

    /** Forget a world that is going away; its entities go with it. */
    static void evict(@Nonnull World world) {
        BY_WORLD.remove(world);
    }

    /** Forget every world (a shutdown, or a test). */
    static void clearAll() {
        BY_WORLD.clear();
    }

    @Nonnull
    HostIndicators host(@Nonnull Ref<EntityStore> hostRef) {
        return hosts.computeIfAbsent(hostRef, r -> new HostIndicators());
    }

    @Nullable
    HostIndicators peekHost(@Nonnull Ref<EntityStore> hostRef) {
        return hosts.get(hostRef);
    }

    @Nonnull
    Map<Ref<EntityStore>, HostIndicators> hosts() {
        return hosts;
    }

    @Nonnull
    Map<Ref<EntityStore>, Marker> markers() {
        return markers;
    }

    /** True when no marker stands in this world, so a per-tick reader can leave at once. */
    boolean hasNoMarkers() {
        return markers.isEmpty();
    }

    /** Forget a marker and the host's own reference to it. */
    void dropMarker(@Nonnull Ref<EntityStore> markerRef) {
        Marker marker = markers.remove(markerRef);
        if (marker != null) {
            marker.host().markers().remove(marker.stateKey(), markerRef);
        }
    }

    /** Forget a host and every marker it had, answering the marker refs so the caller can despawn them. */
    @Nonnull
    Collection<Ref<EntityStore>> dropHost(@Nonnull Ref<EntityStore> hostRef) {
        HostIndicators host = hosts.remove(hostRef);
        if (host == null) {
            return List.of();
        }
        Collection<Ref<EntityStore>> refs = new ArrayList<>(host.markers().values());
        for (Ref<EntityStore> ref : refs) {
            markers.remove(ref);
        }
        host.markers().clear();
        return refs;
    }
}
