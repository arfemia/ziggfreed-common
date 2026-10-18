package com.ziggfreed.common.objectives.producer;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.track.source.ExactAmountPayload;
import com.ziggfreed.common.track.source.VictimPayload;

/**
 * What rides with a {@code DEAL_DAMAGE} moment beyond the victim's id: the creature hit, the engine's own damage
 * (its cause, its source), and the damage the hit dealt, unrounded and capped at what the creature had left, which
 * is what a track source's {@code PerAmount} pays on.
 *
 * @param victimRef the creature hit
 * @param damage    the engine's damage, as applied
 * @param dealt     the damage dealt, capped at the creature's health before the hit
 */
public record DamagePayload(@Nonnull Ref<EntityStore> victimRef, @Nonnull Damage damage, double dealt)
        implements VictimPayload, ExactAmountPayload {

    @Override
    public double exactAmount() {
        return dealt;
    }
}
