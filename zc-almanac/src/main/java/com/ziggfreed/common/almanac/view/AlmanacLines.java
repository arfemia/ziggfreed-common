package com.ziggfreed.common.almanac.view;

import java.time.LocalDate;
import java.time.MonthDay;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.almanac.AlmanacText;
import com.ziggfreed.common.almanac.view.AlmanacView.Scope;
import com.ziggfreed.common.almanac.view.AlmanacView.Season;
import com.ziggfreed.common.almanac.view.AlmanacView.Tally;
import com.ziggfreed.common.almanac.view.AlmanacView.Timing;

/**
 * What the Almanac SAYS about a season's dates, scope and numbers, as client-resolved lines, so every
 * surface that shows a season (its row, its hero chip, a consumer's tile) reads it the same way. Which
 * line a countdown takes is decided here: "On now - N days left" while it runs, "Last day" on its last,
 * "Returns in N days" up to {@value #RETURNS_COUNTDOWN_DAYS} days out, "Returns October 1" further off,
 * "Starts in N days" before its first run, "Between seasons" with no next run. The dates line reads
 * "Every year, ..." for days that are the same every year, naming the run's year when its days move.
 * Numbers bind typed, a month nests as its own key, and a year is text so no locale groups it.
 */
public final class AlmanacLines {

    /** Up to this many days out a return counts down; further off it names its day. */
    public static final int RETURNS_COUNTDOWN_DAYS = 30;

    private AlmanacLines() {
    }

    /** The season's status line: the hero's chip and the list row's second line. */
    @Nonnull
    public static Message chip(@Nonnull Timing timing) {
        if (timing.live()) {
            if (timing.lastDay()) {
                return AlmanacText.line("chip.last_day");
            }
            return timing.daysLeft() == null
                    ? AlmanacText.line("status.live") : AlmanacText.line("chip.live", (long) timing.daysLeft());
        }
        Integer daysUntil = timing.daysUntil();
        if (timing.startsLater() && daysUntil != null) {
            return AlmanacText.line("chip.starts_in", (long) daysUntil);
        }
        if (daysUntil != null && daysUntil <= RETURNS_COUNTDOWN_DAYS) {
            return AlmanacText.line("chip.returns_in", (long) daysUntil);
        }
        LocalDate next = timing.nextStart();
        if (next != null) {
            return AlmanacText.line("chip.returns_on", month(next.getMonthValue()), (long) next.getDayOfMonth());
        }
        return AlmanacText.line("chip.between");
    }

    /**
     * "Every year, October 1 to November 3" for days that are the same every year; "In 2027, March 18 to
     * April 4" for days that move, naming the run the line frames; null when the calendar gave no dates.
     */
    @Nullable
    public static Message window(@Nonnull Timing timing) {
        MonthDay start = timing.windowStart();
        MonthDay end = timing.windowEnd();
        if (start == null || end == null) {
            return null;
        }
        Integer year = timing.windowYear();
        if (year != null) {
            return AlmanacText.line("window.year", String.valueOf(year), month(start.getMonthValue()),
                    (long) start.getDayOfMonth(), month(end.getMonthValue()), (long) end.getDayOfMonth());
        }
        return AlmanacText.line("window", month(start.getMonthValue()), (long) start.getDayOfMonth(),
                month(end.getMonthValue()), (long) end.getDayOfMonth());
    }

    /** A month's name, 1 to 12. */
    @Nonnull
    public static Message month(int month) {
        return AlmanacText.line("month." + month);
    }

    /** A month's short name, 1 to 12, for the year at a glance. */
    @Nonnull
    public static Message shortMonth(int month) {
        return AlmanacText.line("month.short." + month);
    }

    /** "Your 2026 season", or "Every season". */
    @Nonnull
    public static Message scopeHeader(@Nonnull Scope scope) {
        return scope.every()
                ? AlmanacText.line("scope.every") : AlmanacText.line("scope.year", String.valueOf(scope.year()));
    }

    /**
     * The scope header's meta: "You took part" or "You have not taken part yet" for a year; "2 seasons
     * taken part in" for every season, else the same not-yet line.
     */
    @Nonnull
    public static Message scopeMeta(@Nonnull Scope scope, boolean tookPart, long seasonsTakenPart) {
        if (scope.every()) {
            return seasonsTakenPart > 0L
                    ? AlmanacText.line("scope.taken_part_count", seasonsTakenPart) : AlmanacText.line("scope.not_yet");
        }
        return AlmanacText.line(tookPart ? "scope.took_part" : "scope.not_yet");
    }

    /** A tile's caption, "1,204 in all", or null on every season. */
    @Nullable
    public static Message tileCaption(@Nonnull Tally tally) {
        return tally.allSeasons() == null ? null : AlmanacText.line("tile.in_all", (long) tally.allSeasons());
    }

    /** A tile's server line, "8,431 on this server", or null until someone on the server counted one. */
    @Nullable
    public static Message tileServer(@Nonnull Tally tally) {
        return tally.server() == null ? null : AlmanacText.line("tile.server", (long) tally.server());
    }

    /** The keepsake shelf's meta, "1 of 2 years". */
    @Nonnull
    public static Message keepsakesMeta(long earned, long years) {
        return AlmanacText.line("keepsakes.meta", earned, years);
    }

    /** The record card's caption under its seasons figure. */
    @Nonnull
    public static Message recordSeasons(long seasonsTakenPart) {
        return AlmanacText.line("record.seasons", seasonsTakenPart);
    }

    /** The record card's caption under its keepsakes figure. */
    @Nonnull
    public static Message recordKeepsakes(long keepsakes) {
        return AlmanacText.line("record.keepsakes", keepsakes);
    }

    /** "Hallow's Eve is on now", for a consumer's tile. */
    @Nonnull
    public static Message headline(@Nonnull Season season) {
        return AlmanacText.line("headline.live", AlmanacText.authored(season.titleKey(), season.eventId()));
    }
}
