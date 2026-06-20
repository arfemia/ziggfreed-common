package com.ziggfreed.common.instance.match;

import javax.annotation.Nonnull;

/**
 * The immutable outcome of one {@link WinConditionResolver#resolve} call: whether the match should keep
 * going, has been won, or has ended in a draw, plus the winning team index when applicable. PURE DATA -
 * the consumer reacts (end the round, declare a winner, start overtime) on its own thread.
 *
 * <ul>
 *   <li>{@link Status#CONTINUE} - no win condition met yet (or overtime is running); keep playing.
 *       {@code winningTeam} is {@code -1}.</li>
 *   <li>{@link Status#WIN} - a team has won; {@code winningTeam} is that team's index.</li>
 *   <li>{@link Status#DRAW} - the match ended with no single winner; {@code winningTeam} is {@code -1}.</li>
 * </ul>
 */
public record MatchVerdict(
        @Nonnull Status status,
        int winningTeam) {

    /** No team in {@link Status#CONTINUE}/{@link Status#DRAW}. */
    public static final int NO_TEAM = -1;

    /** The three terminal states of a win-condition evaluation. */
    public enum Status { CONTINUE, WIN, DRAW }

    /** Keep playing (no winner yet, or overtime in progress). */
    @Nonnull
    public static MatchVerdict cont() {
        return new MatchVerdict(Status.CONTINUE, NO_TEAM);
    }

    /** Team {@code team} has won. */
    @Nonnull
    public static MatchVerdict win(int team) {
        return new MatchVerdict(Status.WIN, team);
    }

    /** The match ended in a draw (no single winner). */
    @Nonnull
    public static MatchVerdict draw() {
        return new MatchVerdict(Status.DRAW, NO_TEAM);
    }
}
