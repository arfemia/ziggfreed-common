package com.ziggfreed.common.instance.leaderboard;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/**
 * The locale-free chrome text for the {@link LeaderboardPage}: the title, the column
 * headers, the empty state, and the viewer's own strip. The consumer returns pre-built
 * client-resolved {@link Message}s; common stays locale-free.
 *
 * <p>The page paints every line on a {@code .TextSpans}, so any line may carry parameters; a column
 * header's tooltip ({@link #sortTip}) goes through {@code UiText.setTooltip}, which puts a parameterized
 * line on {@code .TooltipTextSpans}, so it may carry them too. The lines the page added for its strip, its lead and
 * its tooltips have defaults from the library's own {@code ziggfreedcommon.leaderboard.lang}, so a board
 * built before them reads right unchanged.
 */
public interface LeaderboardScreenMessages {

    @Nonnull Message title();

    /** One line under the title on how to read the board ("Click a column to rank by it"); null shows none. */
    @Nullable default Message lead() {
        return null;
    }

    /** The heading of the viewer's own strip ("Your standing"). */
    @Nonnull default Message youHeading() {
        return EncounterLeaderboardMessages.line("you.heading");
    }

    /** The viewer's place as a figure ("#3"); the rank binds as a typed number. */
    @Nonnull default Message youPlace(int rank) {
        return EncounterLeaderboardMessages.line("you.place", rank);
    }

    /** The caption under the viewer's place ("Your rank"). */
    @Nonnull default Message youPlaceLabel() {
        return EncounterLeaderboardMessages.line("you.place.label");
    }

    /** How many players the board holds, beside the viewer's place; the count binds as a typed number. */
    @Nonnull default Message youPlayers(int players) {
        return EncounterLeaderboardMessages.line("you.players", players);
    }

    /**
     * A sortable column header's tooltip when the column's own words have none (the header's label key with
     * {@code .tip} added, when the server ships one).
     */
    @Nonnull default Message sortTip() {
        return EncounterLeaderboardMessages.line("tip.sort");
    }

    // ---- Optional leading row labels (default null = no label shown). Clarify which axis each
    // selector row drives; the consumer overrides to name its axes (Kweebec: Difficulty / Players). ----

    /** Leading label for the PRIMARY tab axis (e.g. "Difficulty"); null hides it. */
    @Nullable default Message primaryAxisLabel() {
        return null;
    }

    /** Leading label for the SECONDARY tab axis (e.g. "Players"); null hides it. */
    @Nullable default Message secondaryAxisLabel() {
        return null;
    }

    /** Leading label for the sort-toggle row (e.g. "Sort"); null hides it. */
    @Nullable default Message sortLabel() {
        return null;
    }

    /** Leading label for the Rankings/Stats view toggle (e.g. "View"); null hides it. */
    @Nullable default Message viewLabel() {
        return null;
    }

    @Nonnull Message colRank();

    @Nonnull Message colPlayer();

    @Nonnull Message colScore();

    /** The cumulative total-points column header (Rankings view). */
    @Nonnull Message colTotal();

    @Nonnull Message colTime();

    @Nonnull Message colPlays();

    @Nonnull Message empty();

    /** Sort-toggle button label: rank by best single-run score. */
    @Nonnull Message sortScore();

    /** Sort-toggle button label: rank by cumulative total points. */
    @Nonnull Message sortTotal();

    /** Sort-toggle button label: rank by fastest winning time. */
    @Nonnull Message sortTime();

    /** View-toggle button label: the score rankings. */
    @Nonnull Message viewRankings();

    /** View-toggle button label: the lifetime stats. */
    @Nonnull Message viewStats();

    /**
     * Label for the synthesized "All" tab the page appends to the end of any axis with two or more
     * concrete tabs (e.g. "All"): selecting it aggregates every concrete bucket on that axis, so
     * All+All is the full lifetime roll-up. Required because the page renders this tab itself.
     */
    @Nonnull Message filterAll();

    /**
     * The viewer's own rank + best score in the selected bucket. The page's own strip shows the place and every
     * figure on its own since the strip replaced the footer, so it no longer paints this line, and an
     * implementor need not write it: the default is the viewer's place alone ({@link #youPlace}).
     */
    @Nonnull default Message yourRank(int rank, long bestScore) {
        return youPlace(rank);
    }

    /** The viewer's strip when they have no ranked entry in the selected bucket. */
    @Nonnull Message yourRankNone();

    /**
     * A {@code DURATION} stat cell's words for {@code seconds} ("12h 30m", "45m"), painted on the cell's
     * {@code TextSpans}, so it may carry parameters. Null (the default) paints the format's own h:mm.
     */
    @Nullable default Message duration(long seconds) {
        return null;
    }

    /**
     * A {@code SINCE} stat cell's words for the moment {@code epochSeconds} (seconds since the epoch, a first win),
     * read as how long ago it was against the board's own clock ("Today", "3 days ago"), painted on the cell's
     * {@code TextSpans}, so a count binds as a typed number. Null (the default) paints the format's own date.
     */
    @Nullable default Message since(long epochSeconds) {
        return null;
    }
}
