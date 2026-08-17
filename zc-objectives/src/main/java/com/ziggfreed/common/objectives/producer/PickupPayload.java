package com.ziggfreed.common.objectives.producer;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.event.events.ecs.InteractivelyPickupItemEvent;
import com.ziggfreed.common.progress.runtime.MomentPayload;

/**
 * What rides with a {@code PICKUP_ITEM} moment beyond the item id: the native event, whose
 * {@code ItemStack} is the picked-up stack itself, for a reaction that needs the stack rather than
 * only its id (a bonus roll duplicating what was picked up).
 *
 * @param event the engine's own pickup event, exactly as {@link ZigPickupProducer} saw it
 */
public record PickupPayload(@Nonnull InteractivelyPickupItemEvent event) implements MomentPayload {
}
