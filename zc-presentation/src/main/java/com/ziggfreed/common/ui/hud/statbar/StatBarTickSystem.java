package com.ziggfreed.common.ui.hud.statbar;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Repaints every player's stat bars at their HUD's own cadence ({@link StatBarHud}, five times a second at most, only
 * what changed pushed): a pool refills between any two moments a mod could report, so the bars are read rather than
 * told. A player with no stat bar HUD, or with a paint not yet due, costs one map read. Registered once, by
 * {@link StatBarBootstrap} (the system registry is class-keyed).
 */
public final class StatBarTickSystem extends EntityTickingSystem<EntityStore> {

    @Nonnull
    private final Query<EntityStore> query = Query.and(PlayerRef.getComponentType(), Player.getComponentType());

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        PlayerRef playerRef = chunk.getComponent(index, PlayerRef.getComponentType());
        Player player = chunk.getComponent(index, Player.getComponentType());
        if (playerRef == null || player == null) {
            return;
        }
        Ref<EntityStore> ref = chunk.getReferenceTo(index);
        StatBars.paint(store, ref, player, playerRef, false);
    }
}
