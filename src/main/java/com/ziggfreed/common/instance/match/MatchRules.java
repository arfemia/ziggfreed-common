package com.ziggfreed.common.instance.match;

/**
 * The immutable rule set behind one PvP win condition: which combination of elimination, score cap,
 * round timer, and sudden-death overtime decides the match. PURE DATA - no engine types, no i18n, no
 * hardcoded ids; the consumer maps its own mode/win-condition enum onto these flags. Fed to
 * {@link WinConditionResolver#resolve} each evaluation alongside the live {@link MatchSnapshot}.
 *
 * <p>Field meaning (see the resolution precedence in {@link WinConditionResolver}):
 * <ul>
 *   <li>{@code elimination} - when true, a team with zero eligible members is OUT; last team standing
 *       wins (a simultaneous wipe is a draw, or overtime if {@code suddenDeathSeconds > 0}).</li>
 *   <li>{@code scoreToWin} - the score cap; reaching or passing it wins. {@code 0} disables the cap.</li>
 *   <li>{@code timerSeconds} - the round length; at/after it the highest score wins. {@code 0} means the
 *       timer is effectively expired from the start (score/elimination still decide).</li>
 *   <li>{@code suddenDeathSeconds} - overtime granted past {@code timerSeconds} (and on a tie/simultaneous
 *       wipe) before a draw is declared. {@code 0} disables overtime (an unbroken tie is a draw).</li>
 * </ul>
 */
public record MatchRules(
        boolean elimination,
        int scoreToWin,
        int timerSeconds,
        int suddenDeathSeconds) {

    /** Whether the score cap is active. */
    public boolean hasScoreCap() {
        return scoreToWin > 0;
    }

    /** Whether overtime is granted before a tie/simultaneous-wipe becomes a draw. */
    public boolean hasSuddenDeath() {
        return suddenDeathSeconds > 0;
    }
}
