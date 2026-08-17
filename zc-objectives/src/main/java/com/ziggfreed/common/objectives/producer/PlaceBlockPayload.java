package com.ziggfreed.common.objectives.producer;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.event.events.ecs.PlaceBlockEvent;
import com.ziggfreed.common.progress.runtime.MomentPayload;

/**
 * What rides with a {@code PLACE_BLOCK} moment beyond the placed item's id: the native event, for a
 * reaction that needs the position or the stack it was placed from.
 *
 * @param event the engine's own place event, exactly as {@link ZigPlaceBlockProducer} saw it
 */
public record PlaceBlockPayload(@Nonnull PlaceBlockEvent event) implements MomentPayload {
}
