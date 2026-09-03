package com.ziggfreed.common.encounter.seam;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * "This attacker, which is not a player, acts for THAT player": the question the damage ledger asks
 * before it lets a hit by a turret, a summon or a pet go uncredited. Declared here, filled by the
 * wiring root with the progression runtime's composed kill attribution, so a mod that already
 * attributes its spawned things for kill credit attributes them for encounter credit too, with
 * nothing new to register.
 *
 * <p>Asked on the world thread, inside the damage dispatch. Answer null for an attacker this fill
 * knows nothing about, and only ever a PLAYER's ref: the ledger checks that the answered entity
 * really is one before it credits anything.
 */
@FunctionalInterface
public interface EncounterAttribution {

    /** Attributes nothing: the posture with no fill. */
    EncounterAttribution NONE = (store, attackerRef) -> null;

    /** The player {@code attackerRef} acts for, or null when unknown. */
    @Nullable
    Ref<EntityStore> actsFor(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> attackerRef);
}
