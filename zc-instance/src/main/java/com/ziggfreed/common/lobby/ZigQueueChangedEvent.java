package com.ziggfreed.common.lobby;

import javax.annotation.Nonnull;

import com.hypixel.hytale.event.IEvent;

/**
 * A matchmaking queue changed: the native face of {@link QueueListener#onChange}, carrying exactly
 * the snapshot a listener is handed.
 *
 * <p>Synchronous {@code IEvent<Void>} POJO on the shared engine event bus, fired by every
 * {@link MatchmakingQueue} right after its registered listeners whenever its membership, state or
 * countdown changes (a join, a leave, each countdown second, the launch's state change and the close).
 * Filter on {@link QueueSnapshot#key()}. Built only when somebody is listening ({@link QueueEvents}).
 *
 * <p><b>Threading, the listener's own contract.</b> It fires on the queue's mutator path, often under
 * the queue's lock and on the scheduler's thread: a listener stays cheap and packet-only, never a
 * Store or Ref read and never a blocking call.
 */
public final class ZigQueueChangedEvent implements IEvent<Void> {

    private final QueueSnapshot snapshot;

    public ZigQueueChangedEvent(@Nonnull QueueSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    /** The queue as it stands after the change. */
    @Nonnull
    public QueueSnapshot snapshot() {
        return snapshot;
    }
}
