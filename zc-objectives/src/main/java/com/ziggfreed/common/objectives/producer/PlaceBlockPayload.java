package com.ziggfreed.common.objectives.producer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3i;

import com.hypixel.hytale.server.core.event.events.ecs.PlaceBlockEvent;
import com.ziggfreed.common.track.source.SpotPayload;

/**
 * What rides with a {@code PLACE_BLOCK} moment beyond the placed item's id: the native event, for a
 * reaction that needs the position or the stack it was placed from. A track source reads the position as the
 * moment's spot ({@link SpotPayload}): its {@code StandSeconds} waits on the block standing there.
 *
 * @param event the engine's own place event, exactly as {@link ZigPlaceBlockProducer} saw it
 */
public record PlaceBlockPayload(@Nonnull PlaceBlockEvent event) implements SpotPayload {

    @Override
    @Nullable
    public Vector3i spot() {
        return event.getTargetBlock();
    }
}
