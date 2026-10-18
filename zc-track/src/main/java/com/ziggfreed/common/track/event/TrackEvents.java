package com.ziggfreed.common.track.event;

import javax.annotation.Nonnull;

import com.ziggfreed.common.event.NativeEventSeam;
import com.ziggfreed.common.track.TrackChange;

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
}
