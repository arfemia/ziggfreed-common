package com.ziggfreed.common.objectives.marker;

import java.util.UUID;

import javax.annotation.Nonnull;

/**
 * One surface of the library's quest marks, recomputed for one viewer at a time by {@link QuestMarkers}:
 * on the world thread, on every quest event, at player ready and on the slow sweep.
 */
@FunctionalInterface
public interface QuestMarkerListener {

    /** Recompute what {@code scope}'s viewer sees from this surface. World thread; guarded by the hub. */
    void evaluate(@Nonnull QuestMarkerScope scope);

    /** Drop everything held for a viewer who left. Any thread. */
    default void forget(@Nonnull UUID viewerId) {
    }
}
