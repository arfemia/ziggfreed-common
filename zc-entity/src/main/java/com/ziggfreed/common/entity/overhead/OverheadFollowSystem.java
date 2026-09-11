package com.ziggfreed.common.entity.overhead;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.cast.WorldEvictors;

/**
 * The throttled half of an overhead indicator: once per world per tick the engine calls this, and
 * on the world's cadence ({@link OverheadIndicators#followCadenceMs}, a quarter second by default)
 * it runs one {@link OverheadIndicators#pass}: expiries, take-downs, missing markers and the move
 * after a host that walked.
 *
 * <p>It rides the engine's own query-less {@link TickingSystem}, which is called exactly once per
 * store per tick with no per-entity fan-out, and takes the tick's command buffer the one way a
 * query-less system can: {@code Store.forEachChunk} threads one buffer to every chunk callback and
 * flushes it afterwards, so the first callback runs the pass and the rest are skipped. A world
 * with nothing shown in it costs a map read; a world whose cadence has not come round costs a
 * clock read.
 */
public final class OverheadFollowSystem extends TickingSystem<EntityStore> {

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        OverheadRegistry registry = OverheadRegistry.peek(WorldEvictors.worldOf(store));
        if (registry == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < registry.nextFollowAtMs) {
            return;
        }
        registry.nextFollowAtMs = now + OverheadIndicators.followCadenceMs();
        boolean[] fired = {false};
        store.forEachChunk((chunk, commandBuffer) -> {
            if (fired[0]) {
                return;
            }
            fired[0] = true;
            OverheadIndicators.pass(commandBuffer, registry, now);
        });
    }
}
