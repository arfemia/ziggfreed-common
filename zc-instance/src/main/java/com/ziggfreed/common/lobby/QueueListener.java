package com.ziggfreed.common.lobby;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;

/**
 * A consumer hook a UI page (or a HUD) registers on a {@link MatchmakingQueue} to be
 * told when the queue changes, so an open queue screen can re-render without polling.
 * Register with {@link MatchmakingQueue#addListener} and remove on page close.
 *
 * <p><b>Threading contract (HARD):</b> callbacks fire on the queue's mutator path,
 * exactly like the queue's own {@code Notify}/{@code EventTitles} delivery - they MUST
 * be cheap and <b>packet-only</b> (a {@code sendUpdate}/packet write is fine), NEVER a
 * Store/Ref read or a blocking call, or they break the lobby's packet-only thread rule.
 * The queue guards every callback in a try/catch, so a throwing listener degrades to a
 * no-op rather than corrupting the state machine.
 */
public interface QueueListener {

    /** The queue's membership / state / countdown changed; re-render from {@code snapshot}. */
    default void onChange(@Nonnull QueueSnapshot snapshot) {
    }

    /**
     * The queue launched (the party is being handed to the launcher); an open page
     * should close itself. Fired OFF the queue lock with the re-validated party.
     */
    default void onLaunch(@Nonnull QueueKey key, @Nonnull UUID initiator, @Nonnull List<UUID> party) {
    }
}
