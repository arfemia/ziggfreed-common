package com.ziggfreed.common.progress.runtime;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * "This attacker, which is not a player, acts for THAT player." The seam a kill producer asks
 * before it gives up on a kill nothing player-shaped landed: a turret, a totem, a summoned creature,
 * a pet - anything a mod spawns on a player's behalf whose own hits and kills carry the spawned
 * entity as the damage source.
 *
 * <p>Without it a kill by such an attacker produces no moment at all: the producer reads the
 * attacker's own {@code PlayerRef}, finds none, and stops. With it the moment fires for the player
 * the answer names, credited exactly as if they had landed the blow, and every reaction and both
 * engines see it on those terms.
 *
 * <p><b>Contributions STACK, first real answer wins.</b> Every registered attribution is asked in
 * registration order and the first non-null answer stands, so two mods with different spawned
 * things each attribute their own and neither has to know the other exists; one that THROWS is
 * skipped with a warn and the next is asked. Nothing registered means a non-player attacker credits
 * nobody, which is what a bare server has always done.
 *
 * <p>Asked on the world thread, inside the death dispatch. Answer null for an attacker this
 * contribution knows nothing about, and only ever a PLAYER's ref: the producer checks that the
 * answered entity really is one before it credits anything.
 */
@FunctionalInterface
public interface KillAttribution {

    /** Attributes nothing: the composed answer on a runtime nobody registered one into. */
    KillAttribution NONE = (store, attackerRef) -> null;

    /**
     * The player {@code attackerRef} acts for, or null when this contribution does not know it.
     *
     * @param store       the store both entities live in
     * @param attackerRef the non-player entity the engine named as the damage source
     */
    @Nullable
    Ref<EntityStore> actsFor(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> attackerRef);
}
