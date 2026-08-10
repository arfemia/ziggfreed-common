package com.ziggfreed.common.lobby;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;

/**
 * An immutable read-only view of a {@link MatchmakingQueue} at one instant, the only
 * thing a UI page should render from. Built under the queue lock ({@link
 * MatchmakingQueue#snapshot()}) so {@code members} is a stable copy and the renderer
 * never touches the lock-guarded internals.
 *
 * <p>Carries no engine/world types - just {@code UUID}s + the {@link QueueState} + the
 * {@link LobbyConfig} knobs, so a queue screen can show the roster, an {@code X/Y}
 * count, the state, the "launching in N" countdown, and a derived wait estimate without
 * a Store/Ref read. Usernames are resolved live at render time (the leaderboard-page
 * pattern), never carried here.
 */
public record QueueSnapshot(@Nonnull QueueKey key, @Nonnull QueueState state,
                            @Nonnull List<UUID> members, int countdownRemaining,
                            @Nonnull LobbyConfig config) {

    /** The number of players currently in the queue. */
    public int size() {
        return members.size();
    }
}
