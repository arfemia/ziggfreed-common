package com.ziggfreed.common.playerstats.system;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.playerstats.PlayerStats;
import com.ziggfreed.common.playerstats.ZcStats;
import com.ziggfreed.common.util.SafeLog;

/**
 * Counts a player's death into the {@value ZcStats#DEATHS} stat. Stat-only: a player's death is no
 * moment the shared producers fire (the kill producer skips a player victim), so the store counts it
 * here, once per death, on the world thread the death lands on.
 */
public final class PlayerDeathStatSystem extends DeathSystems.OnDeathSystem {

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }

    @Override
    public void onComponentAdded(@Nonnull Ref<EntityStore> ref, @Nonnull DeathComponent death,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        try {
            PlayerStats.add(store, ref, ZcStats.DEATHS, null, 1L);
        } catch (Throwable t) {
            SafeLog.warn("[stats] a player's death could not be counted", t);
        }
    }
}
