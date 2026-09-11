package com.ziggfreed.common.entity.overhead;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems.EntityViewer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.cast.WorldEvictors;

/**
 * The per-viewer half of an overhead indicator: inside the engine's own visibility pass, after it
 * has collected what each viewer can see and before anything is sent, take every marker entity
 * out of the visible set of a viewer it is not for.
 *
 * <p>This is the extension point the engine names for exactly this ("other systems can run to
 * filter visible entities", the shape its own hidden-players filter takes): the group is the
 * find-visible group, the dependency is after the collector, the query is the viewer. The engine
 * rebuilds each viewer's visible set every tick, so this runs every tick too, but it walks only the
 * world's few markers and asks one map read per marker, never the viewer's whole set; a world with
 * no marker standing costs one null check.
 *
 * <p>Nothing else is needed for the despawn: the engine's send phase compares what a viewer was
 * sent against what they can see, and a marker missing from the second set is sent as removed.
 */
public final class OverheadVisibilityFilter extends EntityTickingSystem<EntityStore> {

    @Nonnull private final ComponentType<EntityStore, EntityViewer> viewerType;
    @Nonnull private final ComponentType<EntityStore, PlayerRef> playerRefType;
    @Nonnull private final Query<EntityStore> query;
    @Nonnull private final Set<Dependency<EntityStore>> dependencies;

    public OverheadVisibilityFilter(@Nonnull ComponentType<EntityStore, EntityViewer> viewerType) {
        this.viewerType = viewerType;
        this.playerRefType = PlayerRef.getComponentType();
        this.query = Query.and(viewerType, playerRefType);
        this.dependencies = Collections.singleton(
                new SystemDependency<>(Order.AFTER, EntityTrackerSystems.CollectVisible.class));
    }

    @Nullable
    @Override
    public SystemGroup<EntityStore> getGroup() {
        return EntityTrackerSystems.FIND_VISIBLE_ENTITIES_GROUP;
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        OverheadRegistry registry = OverheadRegistry.peek(WorldEvictors.worldOf(store));
        if (registry == null || registry.hasNoMarkers()) {
            return;
        }
        EntityViewer viewer = archetypeChunk.getComponent(index, viewerType);
        PlayerRef playerRef = archetypeChunk.getComponent(index, playerRefType);
        if (viewer == null || playerRef == null) {
            return;
        }
        UUID viewerId = playerRef.getUuid();
        if (viewerId == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<Ref<EntityStore>, OverheadRegistry.Marker> entry : registry.markers().entrySet()) {
            Ref<EntityStore> markerRef = entry.getKey();
            if (!viewer.visible.contains(markerRef)) {
                continue;
            }
            OverheadRegistry.Marker marker = entry.getValue();
            if (!marker.stateKey().equals(marker.host().stateFor(viewerId, now))) {
                viewer.visible.remove(markerRef);
                viewer.hiddenCount++;
            }
        }
    }
}
