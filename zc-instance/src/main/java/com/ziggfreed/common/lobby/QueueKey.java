package com.ziggfreed.common.lobby;

import java.util.Locale;
import java.util.UUID;

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

    /**
     * A PUBLIC queue: the shared, un-scoped queue for {@code gameId}/{@code presetId}
     * that any player (or party) coalesces into, so a partial party backfills with
     * strangers up to {@code maxParty}.
     */
    @Nonnull
    public static QueueKey publicQueue(@Nonnull String gameId, @Nonnull String presetId) {
        return new QueueKey(gameId, presetId, "");
    }

    /**
     * A PRIVATE queue scoped to one {@code partyId}: a distinct queue only that party
     * reserves into, so it launches with exactly its own members (no strangers). The
     * scope is the party id, reusing the existing {@code scope} partition - no engine
     * change, no parallel queue map.
     */
    @Nonnull
    public static QueueKey privateQueue(@Nonnull String gameId, @Nonnull String presetId, @Nonnull UUID partyId) {
        return new QueueKey(gameId, presetId, partyId.toString());
    }

    @Nonnull
    private static String norm(@Nullable String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}
