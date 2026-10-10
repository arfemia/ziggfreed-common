package com.ziggfreed.common.playerstats.system;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.playerstats.PlayerStats;
import com.ziggfreed.common.playerstats.PlayerStatsComponent;
import com.ziggfreed.common.playerstats.ZcStats;
import com.ziggfreed.common.util.SafeLog;

/**
 * Counts the time a player spends in the world into the {@value ZcStats#PLAYTIME_SECONDS} stat. Each
 * tick's time is carried on the player's own record and committed in whole seconds once a minute has
 * gathered, so the record and the board move once a minute rather than every frame; the seconds still
 * carried when a player leaves are committed by {@link #onPlayerDisconnect}, before the engine takes
 * the player out of the world.
 */
public final class PlaytimeStatSystem extends EntityTickingSystem<EntityStore> {

    /** How many seconds gather before they are committed. */
    public static final double COMMIT_EVERY_SECONDS = 60d;

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return PlayerRef.getComponentType();
    }

    @Override
    public boolean isParallel(int archetypeChunkSize, int taskCount) {
        return false;
    }

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (dt <= 0f) {
            return;
        }
        ComponentType<EntityStore, PlayerStatsComponent> type = PlayerStatsComponent.TYPE;
        PlayerStatsComponent record = type == null ? null : archetypeChunk.getComponent(index, type);
        if (record == null) {
            return;
        }
        long whole = record.accruePlaytime(dt, COMMIT_EVERY_SECONDS);
        if (whole > 0L) {
            PlayerStats.add(store, archetypeChunk.getReferenceTo(index), ZcStats.PLAYTIME_SECONDS, null, whole);
        }
    }

    /**
     * Commit the whole seconds a leaving player still carries. The engine fires this before it takes the
     * player out of the world, and it queues that removal on the player's world, so a commit queued here
     * runs first and rides the player's save.
     */
    public static void onPlayerDisconnect(@Nonnull PlayerDisconnectEvent event) {
        try {
            PlayerRef player = event.getPlayerRef();
            Ref<EntityStore> ref = player == null ? null : player.getReference();
            if (ref == null || !ref.isValid()) {
                return;
            }
            World world = ref.getStore().getExternalData().getWorld();
            if (world == null) {
                return;
            }
            Runnable commit = () -> commitCarried(ref);
            if (world.isInThread()) {
                commit.run();
                return;
            }
            world.execute(commit);
        } catch (Throwable t) {
            SafeLog.warn("[stats] the last playtime of a leaving player could not be committed", t);
        }
    }

    private static void commitCarried(@Nonnull Ref<EntityStore> ref) {
        try {
            if (!ref.isValid()) {
                return;
            }
            Store<EntityStore> store = ref.getStore();
            PlayerStatsComponent record = PlayerStatsComponent.peek(store, ref);
            long whole = record == null ? 0L : record.accruePlaytime(0d, 0d);
            if (whole > 0L) {
                PlayerStats.add(store, ref, ZcStats.PLAYTIME_SECONDS, null, whole);
            }
        } catch (Throwable t) {
            SafeLog.warn("[stats] the last playtime of a leaving player could not be committed", t);
        }
    }
}
