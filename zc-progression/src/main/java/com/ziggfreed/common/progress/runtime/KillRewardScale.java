package com.ziggfreed.common.progress.runtime;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * "A kill of THIS entity, by THAT player, pays this many times its usual reward." The seam a payer of kill
 * rewards asks before it pays, so a companion that makes some creatures tougher or rarer (a scaled mob, an elite
 * it raised) can make their kills pay more without the payer knowing it exists, and without either mod importing
 * the other.
 *
 * <p><b>Contributions MULTIPLY.</b> Every registered scale is asked and the answers multiply together, so two
 * mods that each scale their own kind of creature never have to know about each other; nothing registered means
 * every kill pays as it always has ({@code 1.0}). An answer that is negative or not a number counts as {@code 1.0},
 * and one that THROWS is skipped with a warn, so a broken scale costs only itself and the kill still pays.
 *
 * <p>Asked on the world thread, inside the kill's reaction, with both refs still valid. The library's own payers do
 * not ask it; a payer reads {@link ProgressionRuntime#killRewardScale()} where it pays a kill.
 */
@FunctionalInterface
public interface KillRewardScale {

    /** Scales nothing: the composed answer on a runtime nobody registered one into. */
    KillRewardScale NONE = (store, victimRef, killerRef) -> 1.0;

    /**
     * How many times its usual reward this kill pays: {@code 1.0} for a kill this contribution knows nothing
     * about.
     *
     * @param store     the store the victim lives in
     * @param victimRef the killed entity
     * @param killerRef the player credited with the kill
     */
    double scaleFor(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> victimRef,
                    @Nonnull Ref<EntityStore> killerRef);
}
