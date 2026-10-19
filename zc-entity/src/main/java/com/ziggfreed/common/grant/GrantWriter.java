package com.ziggfreed.common.grant;

import java.util.List;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * What puts one grant kind on an entity: given every grant of its kind a holder should have on that entity right
 * now, it writes exactly those and takes off every other it wrote for that holder before, so writing the same set
 * twice changes nothing (idempotent) and a grant no longer held goes. On the entity's world thread.
 */
public interface GrantWriter {

    /**
     * Make the entity at {@code ref} carry exactly {@code placed} (every one of this writer's kind) for
     * {@code owner}: each under its own key, which starts {@code <owner>:}; anything this writer finds under that
     * prefix and not in {@code placed} is removed. An empty {@code placed} clears the owner's grants of this kind.
     */
    void write(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull String owner,
            @Nonnull List<Placed> placed);
}
