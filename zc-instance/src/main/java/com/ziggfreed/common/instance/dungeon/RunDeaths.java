package com.ziggfreed.common.instance.dungeon;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * Counts a player's death for the dungeon run of the area they stand in ({@link DungeonRuns#died}), which a clear's
 * results screen lists. Once per death, on the world thread it lands on; a player in no area counts nothing.
 */
public final class RunDeaths extends DeathSystems.OnDeathSystem {

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }

    @Override
    public void onComponentAdded(@Nonnull Ref<EntityStore> ref, @Nonnull DeathComponent death,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        try {
            PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
            if (player != null) {
                DungeonRuns.shared().died(player.getUuid());
            }
        } catch (Throwable t) {
            SafeLog.warn("[dungeon] a player's death could not be counted for their dungeon run", t);
        }
    }
}
