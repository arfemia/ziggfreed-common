package com.ziggfreed.common.entity.performer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.protocol.AnimationSlot;

/**
 * A one-shot work-animation to fire on a performer. Carries the full set of knobs the underlying
 * {@code AnimationUtils.playAnimation} packet needs (slot, optional item-animation set, clip id,
 * whether the caster sees their own swing) rather than a bare clip id, so the bare-{@code Holder}
 * backend stays byte-parity with the shipped puppet swing and the NPC backend can drive the SAME
 * direct-{@code AnimationUtils} route (never {@code NPCEntity.playAnimation}, whose model-registered
 * gate silently swallows custom emote ids).
 *
 * @param slot             the animation slot (e.g. {@link AnimationSlot#Emote}).
 * @param itemAnimationsId the item-animation set id, or {@code null}.
 * @param clipId           the clip/animation id to play.
 * @param sendToSelf       whether the owning player also sees the swing (true for a self-visible cast).
 */
public record ClipSpec(@Nonnull AnimationSlot slot, @Nullable String itemAnimationsId,
        @Nonnull String clipId, boolean sendToSelf) {

    /** A self-visible clip on {@code slot} with no item-animation set. */
    @Nonnull
    public static ClipSpec of(@Nonnull AnimationSlot slot, @Nonnull String clipId) {
        return new ClipSpec(slot, null, clipId, true);
    }
}
