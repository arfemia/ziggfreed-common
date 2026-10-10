package com.ziggfreed.common.effect.buff;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.RespawnSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * A player respawned: the engine's own respawn base ({@code RespawnSystems.OnRespawnSystem}, a
 * reaction to the {@code DeathComponent} coming off), beside the engine's
 * {@code ClearEntityEffectsRespawnSystem}, which clears every effect on the same removal. Hands the
 * player to {@link TimedBuffs#onRespawned}, whose refresh is queued on the world thread and so runs
 * after the clear, finds the tiles missing and puts them back. Registered once, by
 * {@link BuffBootstrap} (the system registry is class-keyed).
 */
public final class TimedBuffRespawnSystem extends RespawnSystems.OnRespawnSystem {

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return PlayerRef.getComponentType();
    }

    @Override
    public void onComponentRemoved(@Nonnull Ref<EntityStore> ref, @Nonnull DeathComponent component,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        TimedBuffs.onRespawned(ref);
    }
}
