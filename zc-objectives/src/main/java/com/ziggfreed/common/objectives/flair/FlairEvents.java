package com.ziggfreed.common.objectives.flair;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.event.NativeEventSeam;
import com.ziggfreed.common.subject.Subject;

/**
 * Fires the flair family's native event on the shared engine event bus, under the library-wide
 * contract ({@link NativeEventSeam}): built only when somebody is listening, dispatched
 * synchronously on the calling (world) thread, and never able to take the write that caused it
 * down with it.
 *
 * <p>The one event ({@link ZigFlairChangedEvent}) is fired by {@link FlairUnlocks} alone, and only
 * for a REAL change. A third party listens on the bus with no compile-time dependency beyond the
 * event class; a host running without a bus (a harness, a unit JVM) redirects the family through
 * {@link #publishTo}.
 */
public final class FlairEvents {

    private static final NativeEventSeam SEAM = new NativeEventSeam("[flair]");

    private FlairEvents() {
    }

    /**
     * Route every fire through {@code publisher} instead of the engine bus; null restores the bus.
     * For a host outside a Hytale server and for a test observing what the facade fires. Not for a
     * mod: on a live server the bus is the one place a listener looks.
     */
    public static void publishTo(@Nullable NativeEventSeam.Publisher publisher) {
        SEAM.publishTo(publisher);
    }

    /**
     * {@code who}'s flair set really changed. The event carries the subject's live
     * {@link PlayerRef}; a subject without one (a handle-less harness subject) cannot be announced
     * on the bus and the seam logs the refusal instead of dispatching a half-built event.
     */
    static void fireChanged(@Nonnull Subject who, @Nonnull String flairId, boolean unlocked) {
        SEAM.fire("ZigFlairChanged", ZigFlairChangedEvent.class,
                () -> new ZigFlairChangedEvent(who.id(), liveRef(who), flairId, unlocked));
    }

    @Nonnull
    private static PlayerRef liveRef(@Nonnull Subject who) {
        PlayerRef playerRef = who.handleAs(PlayerRef.class);
        if (playerRef == null) {
            throw new IllegalStateException("the subject '" + who.name()
                    + "' carries no live player reference, so the flair change cannot be announced");
        }
        return playerRef;
    }
}
