package com.ziggfreed.common.instance.leaderboard.statboard;

import java.time.Duration;
import java.time.Instant;

import javax.annotation.Nonnull;

/**
 * The time the stat board reads its windows by: the server's clock, plus an offset an admin sets with
 * {@code /zigleaderboard clock} to try a week's or a month's turnover without waiting for it. The offset
 * is held in memory only, so a restart puts the board back on the server's time.
 */
public final class StatClock {

    @Nonnull
    private static volatile Duration offset = Duration.ZERO;

    private StatClock() {
    }

    /** The board's now. */
    @Nonnull
    public static Instant now() {
        return Instant.now().plus(offset);
    }

    /** How far the board's clock runs from the server's. */
    @Nonnull
    public static Duration offset() {
        return offset;
    }

    /** Run the board's clock {@code by} from the server's; zero puts it back. */
    public static void setOffset(@Nonnull Duration by) {
        offset = by;
    }
}
