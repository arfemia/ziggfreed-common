package com.ziggfreed.common.party;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.event.NativeEventSeam;

/**
 * Where the party family's native event ({@link ZigPartyChangedEvent}) goes: the shared engine event
 * bus, under the library-wide contract ({@link NativeEventSeam}), except that it fires on the
 * mutating thread rather than always a world thread (the event's javadoc).
 *
 * <p>Fired by {@link PartyService} alone. A third party listens on the bus with no compile-time
 * dependency beyond the event class; a host running without a bus (a harness, a unit JVM) redirects
 * the family through {@link #publishTo}.
 */
public final class PartyEvents {

    private static final NativeEventSeam SEAM = new NativeEventSeam("[party]");

    private PartyEvents() {
    }

    /**
     * Route every fire through {@code publisher} instead of the engine bus; null restores the bus.
     * For a host outside a Hytale server and for a test observing what the service fires. Not for a
     * mod: on a live server the bus is the one place a listener looks.
     */
    public static void publishTo(@Nullable NativeEventSeam.Publisher publisher) {
        SEAM.publishTo(publisher);
    }

    /** Is anybody listening for {@link ZigPartyChangedEvent}? Never throws. */
    static boolean listening() {
        return SEAM.listening(ZigPartyChangedEvent.class);
    }

    /** Announce one change. Never throws. */
    static void fireChanged(@Nonnull PartySnapshot snapshot) {
        SEAM.fire("ZigPartyChanged", ZigPartyChangedEvent.class, () -> new ZigPartyChangedEvent(snapshot));
    }
}
