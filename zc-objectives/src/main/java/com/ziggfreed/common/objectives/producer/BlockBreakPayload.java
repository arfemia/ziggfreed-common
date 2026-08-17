package com.ziggfreed.common.objectives.producer;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.ziggfreed.common.progress.runtime.MomentPayload;

/**
 * What rides with a {@code BREAK_BLOCK} moment beyond the block id: the native event itself, for a
 * reaction that needs the block's position (a bonus drop spawned where the block was) or its type.
 *
 * @param event the engine's own break event, exactly as {@link ZigBlockBreakProducer} saw it
 */
public record BlockBreakPayload(@Nonnull BreakBlockEvent event) implements MomentPayload {
}
