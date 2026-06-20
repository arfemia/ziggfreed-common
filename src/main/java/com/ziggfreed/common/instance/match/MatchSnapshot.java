package com.ziggfreed.common.instance.match;

/**
 * An immutable, per-evaluation view of the live match state the {@link WinConditionResolver} reads:
 * each team's eligible (still-in-play) member count, each team's score, and elapsed round time. PURE
 * DATA - no engine types; the consumer assembles it each tick from its own scoreboard/roster and feeds
 * it to {@link WinConditionResolver#resolve} alongside the {@link MatchRules}.
 *
 * <p>Both arrays are TEAM-INDEXED and must be the same length (one slot per team); index {@code t} in
 * {@code eligibleCounts} and {@code scores} is the same team. A "winning team" returned by the resolver
 * is one of these indices.
 *
 * <ul>
 *   <li>{@code eligibleCounts[t]} - how many members of team {@code t} are still in play; {@code 0} means
 *       that team is wiped (only matters when {@link MatchRules#elimination()} is on). The consumer owns
 *       WHO counts as eligible (alive, not spectating, not eliminated, ...).</li>
 *   <li>{@code scores[t]} - team {@code t}'s current score (kills, captures, points - the consumer's
 *       scale).</li>
 *   <li>{@code elapsedSeconds} - whole seconds since the round began (the consumer's clock).</li>
 * </ul>
 */
public record MatchSnapshot(
        int[] eligibleCounts,
        int[] scores,
        int elapsedSeconds) {

    /** How many teams this snapshot describes (the shorter of the two arrays, defensively). */
    public int teamCount() {
        int e = eligibleCounts == null ? 0 : eligibleCounts.length;
        int s = scores == null ? 0 : scores.length;
        return Math.min(e, s);
    }
}
