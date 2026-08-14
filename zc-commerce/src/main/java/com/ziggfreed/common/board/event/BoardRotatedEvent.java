package com.ziggfreed.common.board.event;

import javax.annotation.Nonnull;

import com.hypixel.hytale.event.IEvent;

/**
 * A board has turned over: the contracts on it are a new set.
 *
 * <p>Synchronous {@code IEvent<Void>} POJO on the shared engine event bus. See {@link BoardEvents}
 * for the fire contract (dispatched on the firing thread, skipped entirely when nothing is
 * listening).
 *
 * <p><b>Fired when the turnover is first NOTICED, not on a timer.</b> A board is a pure function of
 * the wall clock, so nothing anywhere is scheduled to roll it - which is exactly why a restart costs
 * a board nothing. The moment therefore belongs to the first look at the board after its period
 * changed, and it fires ONCE for that period however many players look next. A listener that wants
 * to announce a rotation to a whole server gets what it needs; one that wants a wall-clock tick at
 * midnight should keep its own timer, because this library deliberately has none.
 */
public final class BoardRotatedEvent implements IEvent<Void> {

    private final String boardId;
    private final long periodIndex;
    private final long noticedAtMs;

    public BoardRotatedEvent(@Nonnull String boardId, long periodIndex, long noticedAtMs) {
        this.boardId = boardId;
        this.periodIndex = periodIndex;
        this.noticedAtMs = noticedAtMs;
    }

    /** Which board turned over. */
    @Nonnull
    public String boardId() {
        return boardId;
    }

    /** The rotation period now showing, as the board's own cadence numbers it. */
    public long periodIndex() {
        return periodIndex;
    }

    /** When the turnover was noticed, which is at or after the period actually began. */
    public long noticedAtMs() {
        return noticedAtMs;
    }
}
