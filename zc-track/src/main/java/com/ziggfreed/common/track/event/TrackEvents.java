package com.ziggfreed.common.track.event;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.event.NativeEventSeam;
import com.ziggfreed.common.track.TrackChange;
import com.ziggfreed.common.track.boost.BoostRun;

/**
 * Where the track module's native events go: the shared engine bus, through one {@link NativeEventSeam} (built
 * only when something listens, never throwing). A test observes them with {@code publishTo}.
 */
public final class TrackEvents {

    /** The module's event family; a test routes it with {@code publishTo}. */
    public static final NativeEventSeam SEAM = new NativeEventSeam("[track]");

    private TrackEvents() {
    }

    public static void fireChanged(@Nonnull TrackChange change) {
        SEAM.fire("ZigTrackChanged", ZigTrackChangedEvent.class, () -> ZigTrackChangedEvent.of(change));
    }

    /** The picks on {@code trackId} changed for {@code playerId}; built only when something listens. */
    public static void firePicksChanged(@Nonnull UUID playerId, @Nonnull String trackId,
            @Nonnull ZigTrackPicksChangedEvent.Change change, @Nullable String pickId, @Nullable String slot,
            @Nonnull List<String> options) {
        SEAM.fire("ZigTrackPicksChanged", ZigTrackPicksChangedEvent.class,
                () -> new ZigTrackPicksChangedEvent(playerId, trackId, change, pickId, slot, options));
    }

    /** A boost started: {@code boostId}, of {@code scope}'s word, by the player {@code playerId}. */
    public static void fireBoostStarted(@Nonnull String boostId, @Nonnull String scope, @Nonnull UUID playerId,
            @Nonnull BoostRun run) {
        SEAM.fire("ZigBoostStarted", ZigBoostStartedEvent.class, () -> new ZigBoostStartedEvent(boostId, scope,
                playerId, run.percent(), run.startedAt(), run.endsAt(), run.by()));
    }
}
