package com.ziggfreed.common.objectives.flair;

import java.util.UUID;

import javax.annotation.Nonnull;

import com.hypixel.hytale.event.IEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * A player's unlocked-flair set really changed: a flair was unlocked they did not have, or one they
 * had was taken away.
 *
 * <p>Synchronous {@code IEvent<Void>} POJO on the shared engine event bus; see {@link FlairEvents}
 * for the fire contract. It fires from every write path the library owns - the {@code Flair} reward
 * kind and the {@code /zigflair} verbs both go through {@link FlairUnlocks} - and NEVER for a write
 * that changed nothing: granting a flair the player already had is a successful no-op with no event,
 * and so is revoking one they never had.
 *
 * <p>A listener that persists player data elsewhere (a database-backed consumer) marks the player
 * dirty off this event; a listener that renders flairs refreshes what it shows. Both get the live
 * {@link PlayerRef}, so neither has to look the player up again on a thread it may not be on.
 */
public final class ZigFlairChangedEvent implements IEvent<Void> {

    private final UUID playerId;
    private final PlayerRef playerRef;
    private final String flairId;
    private final boolean unlocked;

    public ZigFlairChangedEvent(@Nonnull UUID playerId, @Nonnull PlayerRef playerRef,
                                @Nonnull String flairId, boolean unlocked) {
        this.playerId = playerId;
        this.playerRef = playerRef;
        this.flairId = flairId;
        this.unlocked = unlocked;
    }

    @Nonnull
    public UUID playerId() {
        return playerId;
    }

    /** The player's live reference; every library write path resolves one before it writes. */
    @Nonnull
    public PlayerRef playerRef() {
        return playerRef;
    }

    /** The flair id as stored: lower-cased. */
    @Nonnull
    public String flairId() {
        return flairId;
    }

    /** True when the flair was just unlocked, false when it was just taken away. */
    public boolean unlocked() {
        return unlocked;
    }
}
