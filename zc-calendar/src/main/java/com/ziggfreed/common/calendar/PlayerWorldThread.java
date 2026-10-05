package com.ziggfreed.common.calendar;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/** The hop onto the thread of the world a player stands in, which an attendance credit and a queued banner share. */
public final class PlayerWorldThread {

    private PlayerWorldThread() {
    }

    /**
     * Queue {@code task} on the thread of the world {@code ref}'s entity is in, or nothing when the entity is gone
     * or its world is not alive. A world that has stopped taking tasks throws, as {@code World.execute} does.
     */
    public static void queue(@Nonnull Ref<EntityStore> ref, @Nonnull Runnable task) {
        if (!ref.isValid()) {
            return;
        }
        World world = ref.getStore().getExternalData().getWorld();
        if (world == null || !world.isAlive()) {
            return;
        }
        world.execute(task);
    }
}
