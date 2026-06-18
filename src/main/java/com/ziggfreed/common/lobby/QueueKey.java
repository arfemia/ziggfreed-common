package com.ziggfreed.common.lobby;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The identity of one {@link MatchmakingQueue}: a {@code gameId} (which mod / game
 * owns it), a {@code presetId} (the difficulty / variant being queued for), and an
 * optional {@code scope} (e.g. a world id) so a game can run per-world queues rather
 * than one global queue per preset. All three are normalized (trimmed + lower-cased)
 * so author / call-site casing never splits a queue.
 *
 * <p>A pure value key: no engine types, no mod-specific imports - the lobby stays a
 * {@code UUID}/{@code String} primitive.
 */
public record QueueKey(@Nonnull String gameId, @Nonnull String presetId, @Nonnull String scope) {

    public QueueKey {
        gameId = norm(gameId);
        presetId = norm(presetId);
        scope = norm(scope);
    }

    /** A global (un-scoped) queue for {@code gameId}/{@code presetId}. */
    public QueueKey(@Nonnull String gameId, @Nonnull String presetId) {
        this(gameId, presetId, "");
    }

    @Nonnull
    private static String norm(@Nullable String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}
