package com.ziggfreed.common.world.gather;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Counts each player's break chain ({@link GatherChains}), which {@code GatherRhythm} reads: every block a player breaks
 * by hand steps it on. A block a gather shape breaks for them ({@link ShapedBreaks#active}) is not a swing of theirs,
 * so it never steps the chain.
 */
public final class GatherChainSystem extends EntityEventSystem<EntityStore, BreakBlockEvent> {

    public GatherChainSystem() {
        super(BreakBlockEvent.class);
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return PlayerRef.getComponentType();
    }

    @Override
    public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk, @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull BreakBlockEvent event) {
        if (event.isCancelled() || ShapedBreaks.active()) {
            return;
        }
        PlayerRef player = archetypeChunk.getComponent(index, PlayerRef.getComponentType());
        if (player != null) {
            GatherChains.broke(player.getUuid(), System.currentTimeMillis());
        }
    }
}
