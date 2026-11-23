package com.ziggfreed.common.objectives.producer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3i;

import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.ziggfreed.common.track.source.SpotPayload;

/**
 * What rides with a {@code BREAK_BLOCK} moment beyond the block id: the native event itself, for a
 * reaction that needs the block's position (a bonus drop spawned where the block was) or its type. A track
 * source reads the position as the moment's spot ({@link SpotPayload}).
 *
 * @param event the engine's own break event, exactly as {@link ZigBlockBreakProducer} saw it
 */
public record BlockBreakPayload(@Nonnull BreakBlockEvent event) implements SpotPayload {

    @Override
    @Nullable
    public Vector3i spot() {
        return event.getTargetBlock();
    }
}
