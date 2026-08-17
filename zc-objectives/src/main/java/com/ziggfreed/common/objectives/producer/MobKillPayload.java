package com.ziggfreed.common.objectives.producer;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.progress.runtime.MomentPayload;

/**
 * What rides with a {@code KILL_ENTITY} moment beyond the victim's id. A death has no native event
 * object - the engine hands its death system a ref and a component - so this carries exactly those
 * two: the VICTIM entity (its corpse still holds a position and a facing to drop loot around, a
 * uuid a lethal-hit record is keyed by, and whatever the kill was worth) and the death itself,
 * whose {@code Damage} names the raw attacker the engine saw. The moment's own {@code ref} is the
 * PLAYER credited, which for a turret or summon kill is the owner the attribution seam answered.
 *
 * @param victimRef the entity that died
 * @param death     the death component, carrying the killing {@code Damage}
 */
public record MobKillPayload(@Nonnull Ref<EntityStore> victimRef, @Nonnull DeathComponent death)
        implements MomentPayload {
}
