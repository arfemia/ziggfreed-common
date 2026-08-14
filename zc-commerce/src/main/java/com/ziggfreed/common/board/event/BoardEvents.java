package com.ziggfreed.common.board.event;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.event.IEvent;
import com.hypixel.hytale.event.IEventDispatcher;
import com.hypixel.hytale.server.core.HytaleServer;

import com.ziggfreed.common.util.SafeLog;

/**
 * Fires the board's native event POJOs on the shared engine event bus.
 *
 * <p><b>The contract, identical for every fire:</b> resolve the dispatcher, guard on
 * {@code hasListener()} so a server with no listeners pays nothing at all, then dispatch
 * synchronously on the CALLING thread. Fire from a world-thread context - a listener runs
 * synchronously on the firing thread, so it can resolve a player and then hop if it needs to.
 *
 * <p>The whole body of every fire is guarded. These events are an outbound courtesy: a listener
 * blowing up, or an event bus that is not there yet, must never take a reroll or a page open down
 * with it. A failure is logged and the board carries on.
 *
 * <p>This is the entire cross-mod surface for board moments. A consumer publishes by letting the
 * board fire, and a third party listens with no compile-time dependency on either side beyond these
 * classes.
 */
public final class BoardEvents {

    /**
     * The last rotation period each board was seen in, so a turnover announces itself ONCE however
     * many players notice it.
     *
     * <p>A board is a pure function of the clock with nothing scheduled anywhere, so there is no
     * tick to fire on: the moment is the first look after the period changed, and this map is what
     * keeps that from being every look. It is deliberately in memory only - a restart re-announcing
     * the current period once is harmless, while persisting it would be state a stateless board
     * spent its whole design avoiding.
     */
    private static final Map<String, Long> LAST_SEEN_PERIOD = new ConcurrentHashMap<>();

    private BoardEvents() {
    }

    /**
     * Note which period {@code boardId} is showing, and fire {@link BoardRotatedEvent} when that is
     * a period nobody has reported yet.
     *
     * <p>Called wherever a board is about to be looked at. The FIRST period seen for a board is
     * recorded silently: a server that just started has not rotated anything, and announcing one on
     * boot would be a lie every listener would have to learn to ignore.
     *
     * @return true when this call was the one that reported a turnover
     */
    public static boolean noticeRotation(@Nonnull String boardId, long periodIndex, long nowMs) {
        Long previous = LAST_SEEN_PERIOD.put(boardId, Long.valueOf(periodIndex));
        if (previous == null || previous.longValue() == periodIndex) {
            return false;
        }
        fire("BoardRotated", BoardRotatedEvent.class,
                () -> new BoardRotatedEvent(boardId, periodIndex, nowMs));
        return true;
    }

    /** A player paid to swap one position on a board. Fired only after the swap is committed. */
    public static void fireRerolled(@Nonnull String boardId, @Nonnull UUID playerId, int position,
            @Nullable String replacedBountyId, @Nonnull String newBountyId) {
        fire("BountyRerolled", BountyRerolledEvent.class,
                () -> new BountyRerolledEvent(boardId, playerId, position, replacedBountyId,
                        newBountyId));
    }

    /** Forget every board's last seen period. For a test, and for nothing else. */
    public static void resetForTests() {
        LAST_SEEN_PERIOD.clear();
    }

    /**
     * The one dispatch body every fire shares. The event is BUILT lazily, after the listener check,
     * so a server nobody is listening on never allocates one.
     */
    private static <E extends IEvent<Void>> void fire(@Nonnull String label, @Nonnull Class<E> type,
            @Nonnull Supplier<E> build) {
        try {
            IEventDispatcher<E, E> dispatcher = HytaleServer.get().getEventBus().dispatchFor(type);
            if (dispatcher.hasListener()) {
                dispatcher.dispatch(build.get());
            }
        } catch (Throwable t) {
            SafeLog.warn("[board] failed to fire " + label + " event: " + t.getMessage());
        }
    }
}
