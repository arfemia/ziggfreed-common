package com.ziggfreed.common.entity.title;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Who shows which title, for a reader that is not on that player's world thread: a menu or a
 * leaderboard naming players who may stand in another world, whose entity stores it may not
 * touch. A mirror of {@link ZigTitleComponent#activeTitle()}, seeded at connect, refreshed by every
 * changed write through the title write path, and forgotten at disconnect, so it holds online
 * players only and an offline player reads as showing nothing.
 *
 * <p>Transient and single-process: it is rebuilt from the saved records as players connect, and a
 * fleet sharing one database keeps one per server. Lock-free reads from any thread.
 */
public final class ActiveTitles {

    private static final Map<UUID, String> BY_PLAYER = new ConcurrentHashMap<>();

    private ActiveTitles() {
    }

    /** The lower-cased id of the title {@code playerId} shows, or null for none, offline or unknown. */
    @Nullable
    public static String of(@Nullable UUID playerId) {
        return playerId == null ? null : BY_PLAYER.get(playerId);
    }

    /** Record what {@code playerId} shows now; a null or blank id records that they show nothing. */
    public static void put(@Nonnull UUID playerId, @Nullable String titleId) {
        if (titleId == null || titleId.isBlank()) {
            BY_PLAYER.remove(playerId);
            return;
        }
        BY_PLAYER.put(playerId, titleId.trim().toLowerCase(Locale.ROOT));
    }

    /** Forget a player who left. */
    public static void evict(@Nonnull UUID playerId) {
        BY_PLAYER.remove(playerId);
    }

    /** Forget everybody: a test, or a host shutting down. */
    public static void clear() {
        BY_PLAYER.clear();
    }
}
