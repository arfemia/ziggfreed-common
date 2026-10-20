package com.ziggfreed.common.objectives.producer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.progress.runtime.MomentPayload;
import com.ziggfreed.common.track.source.TrackSources;

/**
 * The one route a credited hit takes once {@link ZigDamageProducer} has read it: unless a consumer's filter keeps it
 * out ({@link DamageHitFilters}), one {@code DEAL_DAMAGE} moment through {@link ProgressDispatch#fire}, so every
 * listener and both engines count the hit once, by the same whole amount (the damage dealt, rounded). Its own class,
 * beside the producer's ECS halves, so the route runs in a unit JVM without the engine's damage classes (the
 * producer's meta key initializes them).
 */
final class DamageMoments {

    private DamageMoments() {
    }

    /**
     * Fire one hit for {@code hit}'s credited player, unless a filter keeps it out: Target the creature's id,
     * Qualifier the attacker's held item (null for none), the amount {@code dealt} rounded to a whole number,
     * {@code payload} carried to every listener.
     */
    static void fire(@Nonnull DamageHit hit, @Nullable CommandBuffer<EntityStore> commandBuffer,
            @Nonnull String target, @Nullable String qualifier, double dealt, @Nullable MomentPayload payload) {
        if (DamageHitFilters.excludes(hit)) {
            return;
        }
        ProgressDispatch.fire(hit.store(), hit.credited(), commandBuffer, TrackSources.DEAL_DAMAGE, target, qualifier,
                Math.round(dealt), payload);
    }
}
