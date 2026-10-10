package com.ziggfreed.common.effect.buff;

import java.util.Collection;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * What a consumer registers with {@link TimedBuffs#register}: the buffs it shows on one player right
 * now. Asked on the player's world thread at every refresh, so it reads the player's own components
 * through {@code store} and answers from state it already holds; it never applies an effect itself.
 */
@FunctionalInterface
public interface TimedBuffSource {

    /**
     * The buffs this source shows on the player now. A buff whose end has passed may be left in; it is
     * skipped. A throw leaves the whole refresh untouched (named once in the log), so a failing source
     * never takes another source's tiles off.
     *
     * @param store the player's store, on its world thread
     * @param ref   the player
     * @return the buffs, never null; empty when this source shows none
     */
    @Nonnull
    Collection<TimedBuff> buffsOf(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref);
}
