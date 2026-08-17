package com.ziggfreed.common.objectives.producer;

import javax.annotation.Nonnull;

import com.ziggfreed.common.instance.metadata.InstanceRoundCompletedEvent;
import com.ziggfreed.common.progress.runtime.MomentPayload;

/**
 * What rides with an {@code INSTANCE_ROUND_ENDED} or {@code INSTANCE_ROUND_WON} moment beyond the
 * {@code <modId>:<modeId>} target and the preset qualifier: the whole completion event, so a
 * reaction can read the round's difficulty, duration, player count and outcome, or who else was in
 * it, none of which the moment's own tuple has room for. The same record is handed to every player
 * the round names; which kind arrived says whether THIS player won.
 *
 * @param event the round-completion event exactly as {@link ZigInstanceRoundProducer} received it
 */
public record InstanceRoundPayload(@Nonnull InstanceRoundCompletedEvent event) implements MomentPayload {
}
