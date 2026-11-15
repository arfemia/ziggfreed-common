package com.ziggfreed.common.objectives.producer;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * Fires {@code PLAYER_DEATH} once for every death of a player, with a blank Target: the player-victim sibling of
 * {@link ZigMobKillProducer}, which skips a player victim. The one producer of the kind, so a death counts once
 * for every reader of it. The {@code Deaths} statistic is zc-stats' own count, which names no moment, so this
 * fire counts it nowhere a second time.
 */
public final class ZigPlayerDeathProducer extends DeathSystems.OnDeathSystem {

    /** The objective kind this producer feeds. */
    public static final String KIND = "PLAYER_DEATH";

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }

    @Override
    public void onComponentAdded(@Nonnull Ref<EntityStore> ref, @Nonnull DeathComponent death,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        try {
            ProgressDispatch.fire(store, ref, commandBuffer, KIND, "", null, 1L, null);
        } catch (Throwable t) {
            SafeLog.warn("[progression] player-death dispatch failed", t);
        }
    }
}
