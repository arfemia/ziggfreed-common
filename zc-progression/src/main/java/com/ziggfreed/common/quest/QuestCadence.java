package com.ziggfreed.common.quest;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.quest.Quest.Repeat;
import com.ziggfreed.common.util.PeriodMath;

/**
 * How often a quest comes round, as the ONE classification every surface reads: a listing's badge,
 * an achievement's "dailies finished" qualifier, a board's rotation label. Nothing else buckets a
 * repeat rule; a consumer imports this and asks {@link #of}.
 *
 * <p><b>The rule.</b> A repeat rule carries up to two clocks, a rolling wait
 * ({@link Repeat#cooldownMs()}) and a calendar window ({@link Repeat#periodMs()}), and the LONGER
 * of the two is what a player experiences as "how often". That length is bucketed against two
 * thresholds, spelled once here:
 *
 * <ul>
 *   <li>{@link #WEEKLY_FROM_MS} - six days or longer reads as weekly, so a calendar week, a rolling
 *   seven-day wait, and a two- or three-week window all read the same way;</li>
 *   <li>{@link #DAILY_FROM_MS} - twenty hours or longer reads as daily, so a calendar day and a
 *   rolling 24-hour wait agree, and a wait trimmed to 22 hours so a player is never a few minutes
 *   short still reads as the daily it is.</li>
 * </ul>
 *
 * <p>Anything shorter is {@link #REPEATABLE} - an eight-hour window, a two-hour wait, and the EMPTY
 * group whose timing something else governs. A quest with no repeat rule at all is {@link #NONE}.
 */
public enum QuestCadence {

    /** A one-shot: no repeat rule at all. */
    NONE,

    /** Comes round again, but on a clock shorter than a day (or on no clock of its own). */
    REPEATABLE,

    /** Comes round about once a day. */
    DAILY,

    /** Comes round about once a week, or less often. */
    WEEKLY;

    /** The shortest "how often" that reads as {@link #DAILY}: twenty hours. */
    public static final long DAILY_FROM_MS = 20L * PeriodMath.HOUR_MS;

    /** The shortest "how often" that reads as {@link #WEEKLY}: six days. */
    public static final long WEEKLY_FROM_MS = 6L * PeriodMath.DAY_MS;

    /** The cadence of {@code repeat}; {@link #NONE} for a one-shot (a null rule). */
    @Nonnull
    public static QuestCadence of(@Nullable Repeat repeat) {
        if (repeat == null) {
            return NONE;
        }
        long howOftenMs = Math.max(repeat.cooldownMs(), repeat.periodMs());
        if (howOftenMs >= WEEKLY_FROM_MS) {
            return WEEKLY;
        }
        return howOftenMs >= DAILY_FROM_MS ? DAILY : REPEATABLE;
    }
}
