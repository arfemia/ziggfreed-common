package com.ziggfreed.common.board.event;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.event.IEvent;

/**
 * A player paid to swap one contract on a board for another.
 *
 * <p>Synchronous {@code IEvent<Void>} POJO on the shared engine event bus. See {@link BoardEvents}
 * for the fire contract (dispatched on the firing thread, skipped entirely when nothing is
 * listening).
 *
 * <p>It fires only after the swap is COMMITTED, so a listener never sees a reroll that was refused,
 * that could produce no alternative, or whose price could not be taken. {@code replacedBountyId} is
 * nullable because a position can be empty when a shelf had fewer candidates than slots.
 */
public final class BountyRerolledEvent implements IEvent<Void> {

    private final String boardId;
    private final UUID playerId;
    private final int position;
    @Nullable private final String replacedBountyId;
    private final String newBountyId;

    public BountyRerolledEvent(@Nonnull String boardId, @Nonnull UUID playerId, int position,
            @Nullable String replacedBountyId, @Nonnull String newBountyId) {
        this.boardId = boardId;
        this.playerId = playerId;
        this.position = position;
        this.replacedBountyId = replacedBountyId;
        this.newBountyId = newBountyId;
    }

    /** Which board the swap happened on. */
    @Nonnull
    public String boardId() {
        return boardId;
    }

    /** Who paid for it. */
    @Nonnull
    public UUID playerId() {
        return playerId;
    }

    /** Which position on the board changed. */
    public int position() {
        return position;
    }

    /** What was there before, or null when the position was empty. */
    @Nullable
    public String replacedBountyId() {
        return replacedBountyId;
    }

    /** What is there now. */
    @Nonnull
    public String newBountyId() {
        return newBountyId;
    }
}
