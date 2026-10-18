package com.ziggfreed.common.track;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Which {@link TrackStore} keeps each kind of subject's climbs. The seam ships filled: {@code "player"} is
 * {@link PlayerTrackStore} (on the player's own entity) from the start, so a server running this library alone
 * needs nothing wired. A later kind (a guild, R583's profiles) registers its own, and a mod keeping players'
 * climbs elsewhere replaces the player store by registering over it, once, at its setup. A kind nothing
 * registered keeps nothing: its reads answer null or {@link TrackRecord#EMPTY} and its writes are refused.
 */
public final class TrackStores {

    private static final Map<String, TrackStore> STORES = new ConcurrentHashMap<>();

    static {
        STORES.put(TrackKey.Subject.PLAYER, PlayerTrackStore.INSTANCE);
    }

    private TrackStores() {
    }

    /** Keep {@code kind}'s climbs in {@code store}, replacing whatever kept them before. At setup. */
    public static void register(@Nonnull String kind, @Nonnull TrackStore store) {
        STORES.put(kind.trim().toLowerCase(Locale.ROOT), store);
    }

    /** The store keeping {@code kind}'s climbs, or null when none does. */
    @Nullable
    public static TrackStore of(@Nonnull String kind) {
        return STORES.get(kind.trim().toLowerCase(Locale.ROOT));
    }

    /** The store keeping {@code key}'s subject's climbs, or null when none does. */
    @Nullable
    public static TrackStore forKey(@Nonnull TrackKey key) {
        return of(key.subject().kind());
    }
}
