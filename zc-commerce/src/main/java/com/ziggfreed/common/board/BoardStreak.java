package com.ziggfreed.common.board;

import javax.annotation.Nonnull;

/**
 * A player's run of days with at least one board contract collected: the run they are on, the longest they have
 * had, and the day they last collected one (days since the epoch, UTC). Kept by the commerce store
 * ({@code CommerceStore.boardStreak}); {@link #after} is the one rule that moves it.
 *
 * @param current      the days in a row up to {@code lastEpochDay}, 0 before the first contract
 * @param best         the longest run ever reached
 * @param lastEpochDay the UTC day the last contract was collected, 0 before the first
 */
public record BoardStreak(long current, long best, long lastEpochDay) {

    /** Nothing collected yet. */
    public static final BoardStreak NONE = new BoardStreak(0L, 0L, 0L);

    /** The UTC day a moment in epoch milliseconds falls on. */
    public static long epochDayOf(long epochMillis) {
        return Math.floorDiv(epochMillis, 86_400_000L);
    }

    /**
     * The streak after a contract collected on {@code epochDay}: one more when the last was the day before, back to
     * 1 after a gap, and this same streak (the same instance) for a second contract on one day. Pure.
     */
    @Nonnull
    public BoardStreak after(long epochDay) {
        if (lastEpochDay == epochDay && current > 0L) {
            return this;
        }
        long next = lastEpochDay == epochDay - 1 && current > 0L ? current + 1L : 1L;
        return new BoardStreak(next, Math.max(best, next), epochDay);
    }

    /** True before the first contract: nothing held. */
    public boolean isEmpty() {
        return current <= 0L && best <= 0L && lastEpochDay <= 0L;
    }
}
