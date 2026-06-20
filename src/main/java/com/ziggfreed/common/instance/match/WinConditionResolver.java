package com.ziggfreed.common.instance.match;

import javax.annotation.Nonnull;

/**
 * The generic strategy table behind every PvP win condition: given the live {@link MatchSnapshot} and
 * the {@link MatchRules}, decide whether the match continues, has been won (and by which team), or is a
 * draw. PURE + engine-free (no Hytale imports, no wall-clock reads, no logging) - the consumer feeds it
 * the live counts/scores/elapsed time each evaluation and reacts to the {@link MatchVerdict}. A
 * consumer's own win-condition enum (e.g. Kweebec's) maps onto these generic rules; this class stays
 * generic and fully unit-testable.
 *
 * <p><b>Stateless + deterministic.</b> {@link #resolve} reads only its two arguments and returns a fresh
 * verdict; call it as often as you like. The CONSUMER owns WHO is eligible, WHAT scores, and what each
 * verdict means (end the round, declare a winner, kick off overtime).
 *
 * <h2>Resolution precedence (evaluated in this exact order)</h2>
 * <ol>
 *   <li><b>Team-wipe gate</b> (only when {@link MatchRules#elimination()}): a team with zero eligible
 *       members is OUT. Exactly one team left -> that team WINS. ALL remaining teams out simultaneously
 *       -> overtime ({@code CONTINUE}) if sudden death is on, else {@code DRAW}.</li>
 *   <li><b>Score cap</b> (only when {@link MatchRules#scoreToWin()} {@code > 0}): if any team is at/above
 *       the cap, the highest scorer WINS; an exact tie at/above the cap goes to the tiebreak (overtime if
 *       sudden death is on, else {@code DRAW}).</li>
 *   <li><b>Timer</b>: before {@code timerSeconds} the match {@code CONTINUE}s; at/after it the highest
 *       score WINS, and an exact tie among the leaders runs overtime (while still inside the sudden-death
 *       window) or is a {@code DRAW}.</li>
 *   <li>Otherwise {@code CONTINUE}.</li>
 * </ol>
 */
public final class WinConditionResolver {

    private WinConditionResolver() {
        // Static strategy table; not instantiable.
    }

    /**
     * Resolve the current match state against the rules. See the class javadoc for the exact precedence.
     *
     * @param snap  the live per-team counts/scores + elapsed time (team-indexed; index {@code t} is the
     *              same team across both arrays)
     * @param rules the active win-condition rule set
     * @return the verdict ({@code CONTINUE} / {@code WIN team} / {@code DRAW}); never null
     */
    @Nonnull
    public static MatchVerdict resolve(@Nonnull MatchSnapshot snap, @Nonnull MatchRules rules) {
        int teamCount = snap.teamCount();

        // ----- STEP 1: TEAM-WIPE GATE (elimination only) -----
        if (rules.elimination() && teamCount > 0) {
            int[] eligible = snap.eligibleCounts();
            int aliveTeams = 0;
            int lastAliveTeam = MatchVerdict.NO_TEAM;
            for (int t = 0; t < teamCount; t++) {
                // A team with zero eligible members is OUT (the consumer owns what "eligible" means).
                if (eligible[t] > 0) {
                    aliveTeams++;
                    lastAliveTeam = t;
                }
            }
            if (aliveTeams == 1) {
                // Exactly one team survives the wipe -> it wins outright.
                return MatchVerdict.win(lastAliveTeam);
            }
            if (aliveTeams == 0) {
                // Every team is wiped on the same evaluation (a simultaneous wipe): overtime if sudden
                // death can still break it, otherwise a draw.
                return rules.hasSuddenDeath() ? MatchVerdict.cont() : MatchVerdict.draw();
            }
            // aliveTeams >= 2: nobody is eliminated out yet; fall through to score/timer.
        }

        // ----- STEP 2: SCORE CAP (scoreToWin > 0 only) -----
        if (rules.hasScoreCap() && teamCount > 0) {
            int[] scores = snap.scores();
            boolean capReached = false;
            for (int t = 0; t < teamCount; t++) {
                if (scores[t] >= rules.scoreToWin()) {
                    capReached = true;
                    break;
                }
            }
            if (capReached) {
                // Someone hit the cap. The highest scorer wins; an exact tie at the top goes to the
                // tiebreak (overtime if sudden death is on, else a draw).
                int leader = soleLeader(scores, teamCount);
                if (leader != MatchVerdict.NO_TEAM) {
                    return MatchVerdict.win(leader);
                }
                return rules.hasSuddenDeath() ? MatchVerdict.cont() : MatchVerdict.draw();
            }
        }

        // ----- STEP 3: TIMER -----
        if (snap.elapsedSeconds() < rules.timerSeconds()) {
            // Still inside regulation time and no cap/wipe decided it -> keep playing.
            return MatchVerdict.cont();
        }
        // At/after the timer: the highest score wins; an exact tie among the leaders runs overtime while
        // still inside the sudden-death window, otherwise it is a draw.
        if (teamCount > 0) {
            int[] scores = snap.scores();
            int leader = soleLeader(scores, teamCount);
            if (leader != MatchVerdict.NO_TEAM) {
                return MatchVerdict.win(leader);
            }
            // Tied leaders at time: grant overtime only while elapsed is still within timer + suddenDeath.
            if (rules.hasSuddenDeath()
                    && snap.elapsedSeconds() < rules.timerSeconds() + rules.suddenDeathSeconds()) {
                return MatchVerdict.cont();
            }
            return MatchVerdict.draw();
        }

        // ----- STEP 4: nothing decided it -----
        return MatchVerdict.cont();
    }

    /**
     * The index of the single team with the strictly-highest score, or {@link MatchVerdict#NO_TEAM} when
     * two or more teams share the top score (an exact tie at the top, which the caller resolves via the
     * sudden-death / draw rules).
     */
    private static int soleLeader(@Nonnull int[] scores, int teamCount) {
        int best = Integer.MIN_VALUE;
        int bestTeam = MatchVerdict.NO_TEAM;
        boolean tied = false;
        for (int t = 0; t < teamCount; t++) {
            int s = scores[t];
            if (s > best) {
                best = s;
                bestTeam = t;
                tied = false;
            } else if (s == best) {
                // Another team matches the current best -> the top is tied (no sole leader yet).
                tied = true;
            }
        }
        return tied ? MatchVerdict.NO_TEAM : bestTeam;
    }
}
