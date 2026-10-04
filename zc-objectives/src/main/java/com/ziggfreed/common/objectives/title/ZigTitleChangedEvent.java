package com.ziggfreed.common.objectives.title;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.event.IEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * A player's titles really changed: one was unlocked or taken away, or the one they show changed.
 *
 * <p>Synchronous {@code IEvent<Void>} POJO on the shared engine event bus; see {@link TitleEvents}.
 * It fires from every write path the library owns (the {@code Title} reward kind, the
 * {@code /zigtitle} verbs, the picker all go through {@link TitleUnlocks}) and NEVER for a write
 * that changed nothing. A listener persisting player data elsewhere marks the player dirty off it;
 * a renderer refreshes what it shows.
 */
public final class ZigTitleChangedEvent implements IEvent<Void> {

    private final UUID playerId;
    private final PlayerRef playerRef;
    private final String titleId;
    private final TitleUnlocks.Outcome change;
    @Nullable private final String activeTitle;

    public ZigTitleChangedEvent(@Nonnull UUID playerId, @Nonnull PlayerRef playerRef,
                                @Nonnull String titleId, @Nonnull TitleUnlocks.Outcome change,
                                @Nullable String activeTitle) {
        this.playerId = playerId;
        this.playerRef = playerRef;
        this.titleId = titleId;
        this.change = change;
        this.activeTitle = activeTitle;
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

    /** The title the write was about, lower-cased (for a take-off, the one taken off). */
    @Nonnull
    public String titleId() {
        return titleId;
    }

    /** What happened: {@code UNLOCKED}, {@code REVOKED}, {@code ACTIVATED} or {@code DEACTIVATED}. */
    @Nonnull
    public TitleUnlocks.Outcome change() {
        return change;
    }

    /** The title the player shows after the write, lower-cased, or null for none. */
    @Nullable
    public String activeTitle() {
        return activeTitle;
    }
}
