package com.ziggfreed.common.progress.runtime;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.progress.ZoneRef;
import com.ziggfreed.common.subject.Subject;

/**
 * One produced moment, exactly as it happened, handed to every {@link MomentListener}.
 *
 * <p>The first five fields are the tuple both engines are dispatched with. The rest is what a
 * REACTION needs that an engine never does: the entity handles to write against, the subjects the
 * runtime resolved (either may be null, and a listener that needs neither must not care), and the
 * producer's own {@link MomentPayload} for whatever the tuple cannot carry.
 *
 * @param kindId             the objective kind, e.g. {@code BREAK_BLOCK}
 * @param target             what it happened to, in that kind's own vocabulary
 * @param qualifier          the optional secondary filter, or null
 * @param amount             the magnitude: 1 for a discrete action, the batch size for a craft
 * @param zone               where the player was, or null when the engine has nothing resolved
 * @param store              the store the player lives in
 * @param ref                the PLAYER this moment is credited to (already redirected through
 *                           any attribution the producer asked, so a turret kill names its owner)
 * @param commandBuffer      the buffer the producing system was handed, or null for a producer
 *                           that has none
 * @param questSubject       the quest-side subject the runtime built, or null when this player
 *                           has none
 * @param achievementSubject the achievement-side subject, on the same terms
 * @param payload            the producer's own record, or null for a moment fired without one
 */
public record Moment(@Nonnull String kindId,
                     @Nonnull String target,
                     @Nullable String qualifier,
                     long amount,
                     @Nullable ZoneRef zone,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull Ref<EntityStore> ref,
                     @Nullable CommandBuffer<EntityStore> commandBuffer,
                     @Nullable Subject questSubject,
                     @Nullable Subject achievementSubject,
                     @Nullable MomentPayload payload) {

    /** The payload as {@code type}, or null when the moment carries none of that shape. */
    @Nullable
    public <T extends MomentPayload> T payload(@Nonnull Class<T> type) {
        return type.isInstance(payload) ? type.cast(payload) : null;
    }
}
