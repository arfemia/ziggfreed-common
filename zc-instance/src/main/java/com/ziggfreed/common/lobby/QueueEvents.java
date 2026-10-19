package com.ziggfreed.common.lobby;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.event.NativeEventSeam;

/**
 * Where the matchmaking family's two native events go ({@link ZigQueueChangedEvent},
 * {@link ZigQueueLaunchedEvent}): the shared engine event bus, under the library-wide contract
 * ({@link NativeEventSeam}), except that they fire on the queue's mutator path rather than a world
 * thread (each event's javadoc).
 *
 * <p>Fired by {@link MatchmakingQueue} alone. A third party listens on the bus with no compile-time
 * dependency beyond the event classes; a host running without a bus (a harness, a unit JVM) redirects
 * the family through {@link #publishTo}.
 */
public final class QueueEvents {

    private static final NativeEventSeam SEAM = new NativeEventSeam("[lobby]");

    private QueueEvents() {
    }

    /**
     * Route every fire through {@code publisher} instead of the engine bus; null restores the bus.
     * For a host outside a Hytale server and for a test observing what a queue fires. Not for a mod:
     * on a live server the bus is the one place a listener looks.
     */
    public static void publishTo(@Nullable NativeEventSeam.Publisher publisher) {
        SEAM.publishTo(publisher);
    }

    static boolean listeningForChanges() {
        return SEAM.listening(ZigQueueChangedEvent.class);
    }

    static boolean listeningForLaunches() {
        return SEAM.listening(ZigQueueLaunchedEvent.class);
    }

    static void fireChanged(@Nonnull QueueSnapshot snapshot) {
        SEAM.fire("ZigQueueChanged", ZigQueueChangedEvent.class, () -> new ZigQueueChangedEvent(snapshot));
    }

    static void fireLaunched(@Nonnull QueueKey key, @Nonnull UUID initiator, @Nonnull List<UUID> party) {
        SEAM.fire("ZigQueueLaunched", ZigQueueLaunchedEvent.class,
                () -> new ZigQueueLaunchedEvent(key, initiator, party));
    }
}
