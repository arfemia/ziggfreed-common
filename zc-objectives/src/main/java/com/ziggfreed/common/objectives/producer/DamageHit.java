package com.ziggfreed.common.objectives.producer;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * One creature hit {@link ZigDamageProducer} is about to produce as {@code DEAL_DAMAGE}, as a
 * {@link DamageHitFilters} filter reads it: the victim's chunk and index in it, the store, the victim, the entity that
 * struck (a player, a turret, an arrow's shooter), the player credited with the hit, and the damage as applied.
 * World thread, inside the damage dispatch: read it, never change it.
 */
public record DamageHit(@Nonnull ArchetypeChunk<EntityStore> chunk, int index, @Nonnull Store<EntityStore> store,
        @Nonnull Ref<EntityStore> victim, @Nonnull Ref<EntityStore> attacker, @Nonnull Ref<EntityStore> credited,
        @Nonnull Damage damage) {
}
