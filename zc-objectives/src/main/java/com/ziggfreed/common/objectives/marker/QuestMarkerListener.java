package com.ziggfreed.common.objectives.marker;

import java.util.UUID;

import javax.annotation.Nonnull;

/**
 * One surface of the library's quest marks, recomputed for one viewer at a time by {@link QuestMarkers}:
 * on the world thread, on every quest event, at player ready and on the slow sweep.
 *
 * <p>A surface listens on the engine bus: {@link QuestMarkers#registerSurface} hangs its two halves there,
 * {@link #evaluate} on {@link ZigQuestMarksEvaluatedEvent} and {@link #forget} on the engine's
 * {@code PlayerDisconnectEvent}. {@link QuestMarkers}' own list of these (its {@code addListener}) is
 * deprecated.
 */
@FunctionalInterface
public interface QuestMarkerListener {

    /** Recompute what {@code scope}'s viewer sees from this surface. World thread; guarded by the hub. */
    void evaluate(@Nonnull QuestMarkerScope scope);

    /** Drop everything held for a viewer who left. Any thread. */
    default void forget(@Nonnull UUID viewerId) {
    }
}
