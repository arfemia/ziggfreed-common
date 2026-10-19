package com.ziggfreed.common.progress.runtime;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.event.NativeEventSeam;

/**
 * Where the moment feed's native event ({@link ZigMomentEvent}) goes: the shared engine event bus,
 * under the library-wide contract ({@link NativeEventSeam}).
 *
 * <p>The feed is a hot path (one fire per action in the game, one per hit for a reaction-only kind),
 * so the fire asks {@link NativeEventSeam#listening} first and builds nothing at all while nobody
 * listens. Fired only by the reaction fan-out, after the registered listeners; a third party listens on
 * the bus with no compile-time dependency beyond the event class, and a host running without a bus (a
 * harness, a unit JVM) redirects the family through {@link #publishTo}.
 */
public final class MomentEvents {

    private static final NativeEventSeam SEAM = new NativeEventSeam("[progression]");

    private MomentEvents() {
    }

    /**
     * Route every fire through {@code publisher} instead of the engine bus; null restores the bus.
     * For a host outside a Hytale server and for a test observing what the feed fires. Not for a
     * mod: on a live server the bus is the one place a listener looks.
     */
    public static void publishTo(@Nullable NativeEventSeam.Publisher publisher) {
        SEAM.publishTo(publisher);
    }

    /** Announce {@code moment}, when anybody is listening. Never throws. */
    static void fire(@Nonnull Moment moment) {
        if (SEAM.listening(ZigMomentEvent.class)) {
            SEAM.fire("ZigMoment", ZigMomentEvent.class, () -> new ZigMomentEvent(moment));
        }
    }
}
