package com.ziggfreed.common.cast;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.util.ZigScheduler;

/**
 * Run a step on an entity's world thread a while from now: the one run-later queue for a step that waits (a windup,
 * each pulse of an area, a refreshed mark), on the library's {@link ZigScheduler} with the entity's world as the
 * executor, so no system has to drain it and it works on a server running the library alone.
 *
 * <p>The entity is read again when the time comes, never when the step is queued: one removed since, or moved into
 * another world's store, drops the step quietly (no step, no log line), so a step never runs against a foreign store.
 * The step gets the live store and reads whatever it needs (a position, a rotation) itself. It runs as a world task,
 * outside any system's tick, so it may write the store directly.
 */
public final class WorldTasks {

    private WorldTasks() {
    }

    /**
     * Run {@code step} on {@code anchor}'s world thread {@code delayMs} from now (a negative wait is none), handing it
     * the store {@code anchor} still lives in. Any thread.
     *
     * @return false when it could not be queued: an unreadable entity, or no scheduler running (a unit test, a server
     *         shutting down)
     */
    public static boolean later(@Nonnull Ref<EntityStore> anchor, long delayMs,
            @Nonnull Consumer<Store<EntityStore>> step) {
        World world;
        try {
            world = WorldEvictors.worldOf(anchor);
        } catch (Throwable t) {
            SafeLog.fine("[cast] a step to run later had no world to run in: " + t.getMessage());
            return false;
        }
        try {
            ZigScheduler.schedule(() -> fire(world, anchor, step), Math.max(0L, delayMs), TimeUnit.MILLISECONDS,
                    world);
            return true;
        } catch (RejectedExecutionException e) {
            SafeLog.fine("[cast] a step to run later was dropped: " + e.getMessage());
            return false;
        }
    }

    /** The step, on the world thread, when its entity is still in that world's store. */
    private static void fire(@Nonnull World world, @Nonnull Ref<EntityStore> anchor,
            @Nonnull Consumer<Store<EntityStore>> step) {
        try {
            Store<EntityStore> store = world.getEntityStore().getStore();
            if (!anchor.isValid() || anchor.getStore() != store) {
                return;
            }
            step.accept(store);
        } catch (Throwable t) {
            SafeLog.fine("[cast] a step run later failed: " + t.getMessage());
        }
    }
}
