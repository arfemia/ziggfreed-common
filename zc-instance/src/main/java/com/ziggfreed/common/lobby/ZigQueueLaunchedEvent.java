package com.ziggfreed.common.lobby;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;

import com.hypixel.hytale.event.IEvent;

/**
 * A matchmaking queue launched: the native face of {@link QueueListener#onLaunch}, carrying exactly
 * what a listener is handed.
 *
 * <p>Synchronous {@code IEvent<Void>} POJO on the shared engine event bus, fired once per launch, off
 * the queue's lock and right after its registered listeners, with the re-validated party (online and
 * not already engaged) just before it is handed to the launcher. The launch can still fail after this
 * (the players are told); a round that really started is the instance's own round event. Built only
 * when somebody is listening ({@link QueueEvents}).
 *
 * <p><b>Threading.</b> It fires on the queue's scheduler thread: a listener stays cheap and
 * packet-only, never a Store or Ref read and never a blocking call.
 */
public final class ZigQueueLaunchedEvent implements IEvent<Void> {

    private final QueueKey key;
    private final UUID initiator;
    private final List<UUID> party;

    public ZigQueueLaunchedEvent(@Nonnull QueueKey key, @Nonnull UUID initiator, @Nonnull List<UUID> party) {
        this.key = key;
        this.initiator = initiator;
        this.party = List.copyOf(party);
    }

    /** Which queue launched. */
    @Nonnull
    public QueueKey key() {
        return key;
    }

    /** The player the launch is made for: the first eligible joiner. */
    @Nonnull
    public UUID initiator() {
        return initiator;
    }

    /** Everyone launched, the initiator first. */
    @Nonnull
    public List<UUID> party() {
        return party;
    }
}
