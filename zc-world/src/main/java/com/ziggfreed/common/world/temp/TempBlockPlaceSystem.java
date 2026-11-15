package com.ziggfreed.common.world.temp;

import javax.annotation.Nonnull;

import org.joml.Vector3i;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.event.events.ecs.PlaceBlockEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * Hears every native placement, by anyone: a placement into a cell that holds a temporary block's record means the
 * temporary block is gone (only an empty or replaceable cell takes a block), so the record is forgotten before the new
 * block lands ({@link TempBlocks#onPlacementInto}) and the crumble sweep never takes a block someone put there since.
 * The event is dispatched before the engine places, so the cell still shows what stood there; a record whose block
 * still stands (a placement the engine is about to refuse) is kept.
 */
public final class TempBlockPlaceSystem extends EntityEventSystem<EntityStore, PlaceBlockEvent> {

    public TempBlockPlaceSystem() {
        super(PlaceBlockEvent.class);
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return Query.any();
    }

    @Override
    public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk, @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull PlaceBlockEvent event) {
        try {
            Vector3i target = event.getTargetBlock();
            if (target != null) {
                TempBlocks.onPlacementInto(store.getExternalData().getWorld(), target.x, target.y, target.z);
            }
        } catch (Throwable t) {
            SafeLog.fine("[temp] a placement could not be read: " + t.getMessage());
        }
    }
}
