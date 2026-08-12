package com.ziggfreed.common.worldmap;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/**
 * The whole of the waypoint mechanism that has nothing to do with a world: the registered
 * {@link WaypointTargetSource}s, the per-viewer snapshot of where each is being pointed, and the
 * rule that turns that snapshot plus a {@link WaypointPositionResolver} into marker descriptions.
 *
 * <p><b>Why it is split out.</b> The two halves run on DIFFERENT THREADS, and the split is what
 * makes that safe to reason about. {@link #refresh} rebuilds a viewer's snapshot on whatever thread
 * the consumer owns its state on (the world thread), while {@link #markerSpecsFor} runs on the map
 * tracker and only READS the snapshot map and the resolver. Nothing here touches an entity store, a
 * world, or a player, so the rule can also be exercised whole without a server.
 *
 * <p>{@link WaypointService} is the same thing bound to a world and the native marker API.
 */
public final class WaypointSnapshots {

    /**
     * One marker to draw: everything needed to build it, with no engine object constructed yet.
     *
     * @param id    unique marker id, built from the provider key, the target, and the position anchor
     * @param icon  client map-marker texture id
     * @param title hover name, or null
     */
    public record MarkerSpec(@Nonnull String id, @Nonnull String icon, double x, double y, double z,
                             @Nullable Message title) {
    }

    /** Joins the parts of a marker id. Reserved inside a provider key, a target id, or an anchor key. */
    public static final String ID_SEPARATOR = ":";

    private final String providerKey;
    private final String defaultIcon;
    private final Consumer<String> warn;

    private final List<WaypointTargetSource> sources = new CopyOnWriteArrayList<>();
    /** viewer -> where they are being pointed. Written on the refresh thread, read on the tracker. */
    private final Map<UUID, List<WaypointTarget>> snapshots = new ConcurrentHashMap<>();

    /**
     * @param providerKey the marker-id prefix, which should be the same mod-prefixed key the
     *                    provider is registered under, so one consumer's markers can never collide
     *                    with another's
     * @param defaultIcon the client texture id a target that names no icon of its own takes
     * @param warn        where a misbehaving source or resolver is reported
     */
    public WaypointSnapshots(@Nonnull String providerKey, @Nonnull String defaultIcon,
                             @Nonnull Consumer<String> warn) {
        this.providerKey = providerKey;
        this.defaultIcon = defaultIcon;
        this.warn = warn;
    }

    /**
     * Add a source. Registering the same instance twice is a no-op, so a consumer may register on
     * every load without accumulating duplicates.
     */
    public void addSource(@Nonnull WaypointTargetSource source) {
        if (!sources.contains(source)) {
            sources.add(source);
        }
    }

    /** Drop a source. Returns true when it was registered. */
    public boolean removeSource(@Nonnull WaypointTargetSource source) {
        return sources.remove(source);
    }

    /** How many sources are registered. */
    public int sourceCount() {
        return sources.size();
    }

    /**
     * Rebuild one viewer's snapshot by asking every source, keeping the FIRST target for any
     * repeated id (registration order is the precedence, so a consumer decides it by registering in
     * the order it wants). A viewer nobody is pointing anywhere is forgotten outright rather than
     * left holding an empty list.
     *
     * <p>A source that throws is reported and skipped; the others still contribute.
     *
     * @return how many targets the viewer now has
     */
    public int refresh(@Nonnull UUID viewerId) {
        List<WaypointTarget> merged = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (WaypointTargetSource source : sources) {
            List<WaypointTarget> targets;
            try {
                targets = source.targetsFor(viewerId);
            } catch (Throwable t) {
                warn.accept("a waypoint source failed: " + t.getMessage());
                continue;
            }
            if (targets == null) {
                continue;
            }
            for (WaypointTarget target : targets) {
                if (target != null && !target.id().isBlank() && seen.add(target.id())) {
                    merged.add(target);
                }
            }
        }
        return set(viewerId, merged);
    }

    /**
     * Replace one viewer's snapshot outright, for a consumer that already knows the answer and does
     * not want the sources asked.
     *
     * @return how many targets the viewer now has
     */
    public int set(@Nonnull UUID viewerId, @Nullable List<WaypointTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            snapshots.remove(viewerId);
            return 0;
        }
        List<WaypointTarget> frozen = List.copyOf(targets);
        snapshots.put(viewerId, frozen);
        return frozen.size();
    }

    /** Forget a viewer's snapshot (they left). */
    public void clear(@Nonnull UUID viewerId) {
        snapshots.remove(viewerId);
    }

    /** Forget every snapshot. */
    public void clearAll() {
        snapshots.clear();
    }

    /** Where this viewer is being pointed right now, as last refreshed. Never null. */
    @Nonnull
    public List<WaypointTarget> targets(@Nonnull UUID viewerId) {
        List<WaypointTarget> targets = snapshots.get(viewerId);
        return targets == null ? List.of() : targets;
    }

    /** How many viewers currently have a snapshot. */
    public int viewerCount() {
        return snapshots.size();
    }

    /**
     * The markers this viewer should see IN THIS WORLD: every snapshot target, resolved through
     * {@code resolver}, with one marker per position it resolves to here.
     *
     * <p>A target that resolves nowhere in this world contributes nothing, which is exactly how a
     * viewer never gets pointed at another world's copy of a place. Marker ids are
     * {@code providerKey:targetId:anchorKey}, so two live copies of the same place stay two markers
     * and a repeat of the same one is dropped.
     *
     * <p>A resolver that throws is reported once per call and that target is skipped.
     */
    @Nonnull
    public List<MarkerSpec> markerSpecsFor(@Nonnull String worldName, @Nonnull UUID viewerId,
                                           @Nonnull WaypointPositionResolver resolver) {
        List<WaypointTarget> targets = targets(viewerId);
        if (targets.isEmpty()) {
            return List.of();
        }
        List<MarkerSpec> out = new ArrayList<>(targets.size());
        Set<String> markerIds = new LinkedHashSet<>();
        for (WaypointTarget target : targets) {
            List<WaypointPosition> positions;
            try {
                positions = resolver.resolve(worldName, target.positionKey());
            } catch (Throwable t) {
                warn.accept("a waypoint position resolver failed for '" + target.positionKey()
                        + "': " + t.getMessage());
                continue;
            }
            if (positions == null) {
                continue;
            }
            String icon = target.icon() == null || target.icon().isBlank() ? defaultIcon : target.icon();
            for (WaypointPosition position : positions) {
                if (position == null) {
                    continue;
                }
                String markerId = providerKey + ID_SEPARATOR + target.id() + ID_SEPARATOR + position.anchorKey();
                if (markerIds.add(markerId)) {
                    out.add(new MarkerSpec(markerId, icon, position.x(), position.y(), position.z(),
                            target.title()));
                }
            }
        }
        return out;
    }
}
