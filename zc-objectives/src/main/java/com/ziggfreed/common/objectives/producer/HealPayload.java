package com.ziggfreed.common.objectives.producer;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.track.source.ExactAmountPayload;

/**
 * What rides with a {@code HEAL_GIVEN} moment beyond its Target: the player healed, and the health the heal is credited
 * for, unrounded (what it restored out of their recent creature damage), which is what a track source's
 * {@code PerAmount} pays on.
 *
 * @param healedRef the player healed (the healer, on a self-heal)
 * @param credited  the health credited, above 0
 */
public record HealPayload(@Nonnull Ref<EntityStore> healedRef, double credited) implements ExactAmountPayload {

    @Override
    public double exactAmount() {
        return credited;
    }
}
