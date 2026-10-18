package com.ziggfreed.common.track;

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Where one kind of subject's climbs are kept (R583): every read and write of track progress goes through
 * the store {@link TrackStores} holds for the key's subject kind, so a later keeping (a profile, a guild's
 * shared record, a database) replaces a store and nothing that reads a climb changes.
 *
 * <p>A LIVE subject (a player in a world here) is read and written on its own thread, at once; any subject,
 * live or not, can be read later through {@link #load}.
 */
public interface TrackStore {

    /**
     * The climb {@code key} names for a live subject, read on that subject's own thread, or null when the
     * subject is not live here (or its record cannot be read). A live subject with nothing on record reads
     * {@link TrackRecord#EMPTY}.
     */
    @Nullable
    TrackRecord get(@Nonnull TrackKey key);

    /**
     * Keep {@code record} for {@code key}'s live subject, on its own thread. False when the subject is not live
     * here or nothing could be written.
     */
    boolean put(@Nonnull TrackKey key, @Nonnull TrackRecord record);

    /**
     * The climb {@code key} names whether or not its subject is live, from any thread: a live subject's answer
     * at once (or on its thread), anyone else's from wherever the store keeps it. Never fails: a subject with
     * nothing on record, or one that cannot be read, answers {@link TrackRecord#EMPTY}.
     */
    @Nonnull
    CompletableFuture<TrackRecord> load(@Nonnull TrackKey key);
}
