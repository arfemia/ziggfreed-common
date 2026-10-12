package com.ziggfreed.common.objectives.producer;

import javax.annotation.Nonnull;

import com.ziggfreed.common.instance.metadata.InstanceRoundStartedEvent;
import com.ziggfreed.common.progress.runtime.MomentPayload;

/**
 * What rides with an {@code INSTANCE_ROUND_STARTED} moment beyond the {@code <modId>:<modeId>} target and the
 * preset qualifier: the whole start event, so a reaction can read the round's player count or who else it
 * named. The same record is handed to every player the start names; a finished round rides as
 * {@link InstanceRoundPayload} instead.
 *
 * @param event the round-start event exactly as {@link ZigInstanceRoundProducer} received it
 */
public record InstanceRoundStartedPayload(@Nonnull InstanceRoundStartedEvent event) implements MomentPayload {
}
