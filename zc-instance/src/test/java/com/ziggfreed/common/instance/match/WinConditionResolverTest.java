package com.ziggfreed.common.instance.match;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Deterministic unit tests for the PURE {@link WinConditionResolver} strategy table, one cluster per
 * precedence branch (team-wipe gate, score cap, timer, sudden death).
 */
class WinConditionResolverTest {

    private static MatchSnapshot snap(int[] eligible, int[] scores, int elapsed) {
        return new MatchSnapshot(eligible, scores, elapsed);
    }

    // ----- STEP 1: TEAM-WIPE GATE -----

    @Test
    void eliminationLastTeamStandingWins() {
        // Team 0 wiped, team 1 alive -> team 1 wins.
        MatchRules rules = new MatchRules(true, 0, 300, 0);
        MatchVerdict v = WinConditionResolver.resolve(snap(new int[] {0, 2}, new int[] {0, 0}, 10), rules);
        assertEquals(MatchVerdict.Status.WIN, v.status());
        assertEquals(1, v.winningTeam());
    }

    @Test
    void simultaneousWipeIsDrawWithoutSuddenDeath() {
        MatchRules rules = new MatchRules(true, 0, 300, 0);
        MatchVerdict v = WinConditionResolver.resolve(snap(new int[] {0, 0}, new int[] {3, 3}, 10), rules);
        assertEquals(MatchVerdict.Status.DRAW, v.status());
        assertEquals(MatchVerdict.NO_TEAM, v.winningTeam());
    }

    @Test
    void simultaneousWipeIsOvertimeWithSuddenDeath() {
        MatchRules rules = new MatchRules(true, 0, 300, 60);
        MatchVerdict v = WinConditionResolver.resolve(snap(new int[] {0, 0}, new int[] {3, 3}, 10), rules);
        assertEquals(MatchVerdict.Status.CONTINUE, v.status());
    }

    @Test
    void twoTeamsAliveFallsThroughToScoreOrTimer() {
        // Nobody eliminated yet, no cap, inside timer -> CONTINUE.
        MatchRules rules = new MatchRules(true, 0, 300, 0);
        MatchVerdict v = WinConditionResolver.resolve(snap(new int[] {2, 2}, new int[] {1, 0}, 10), rules);
        assertEquals(MatchVerdict.Status.CONTINUE, v.status());
    }

    @Test
    void eliminationDisabledIgnoresWipe() {
        // elimination off: a zero-count team does NOT win the other; cap off, inside timer -> CONTINUE.
        MatchRules rules = new MatchRules(false, 0, 300, 0);
        MatchVerdict v = WinConditionResolver.resolve(snap(new int[] {0, 2}, new int[] {0, 0}, 10), rules);
        assertEquals(MatchVerdict.Status.CONTINUE, v.status());
    }

    // ----- STEP 2: SCORE CAP -----

    @Test
    void scoreCapHighestScorerWins() {
        MatchRules rules = new MatchRules(false, 10, 300, 0);
        MatchVerdict v = WinConditionResolver.resolve(snap(new int[] {2, 2}, new int[] {10, 4}, 30), rules);
        assertEquals(MatchVerdict.Status.WIN, v.status());
        assertEquals(0, v.winningTeam());
    }

    @Test
    void scoreCapPastCapStillWins() {
        // A team blew past the cap -> still wins (>= cap).
        MatchRules rules = new MatchRules(false, 10, 300, 0);
        MatchVerdict v = WinConditionResolver.resolve(snap(new int[] {2, 2}, new int[] {3, 12}, 30), rules);
        assertEquals(MatchVerdict.Status.WIN, v.status());
        assertEquals(1, v.winningTeam());
    }

    @Test
    void scoreCapTieAtCapIsDrawWithoutSuddenDeath() {
        MatchRules rules = new MatchRules(false, 10, 300, 0);
        MatchVerdict v = WinConditionResolver.resolve(snap(new int[] {2, 2}, new int[] {10, 10}, 30), rules);
        assertEquals(MatchVerdict.Status.DRAW, v.status());
    }

    @Test
    void scoreCapTieAtCapIsOvertimeWithSuddenDeath() {
        MatchRules rules = new MatchRules(false, 10, 300, 60);
        MatchVerdict v = WinConditionResolver.resolve(snap(new int[] {2, 2}, new int[] {11, 11}, 30), rules);
        assertEquals(MatchVerdict.Status.CONTINUE, v.status());
    }

    @Test
    void scoreCapDisabledByZero() {
        // scoreToWin 0 -> cap never fires; inside timer -> CONTINUE even with a big lead.
        MatchRules rules = new MatchRules(false, 0, 300, 0);
        MatchVerdict v = WinConditionResolver.resolve(snap(new int[] {2, 2}, new int[] {99, 0}, 30), rules);
        assertEquals(MatchVerdict.Status.CONTINUE, v.status());
    }

    // ----- STEP 3: TIMER -----

    @Test
    void beforeTimerContinues() {
        MatchRules rules = new MatchRules(false, 0, 300, 0);
        MatchVerdict v = WinConditionResolver.resolve(snap(new int[] {2, 2}, new int[] {5, 1}, 299), rules);
        assertEquals(MatchVerdict.Status.CONTINUE, v.status());
    }

    @Test
    void atTimerHighestScoreWins() {
        MatchRules rules = new MatchRules(false, 0, 300, 0);
        MatchVerdict v = WinConditionResolver.resolve(snap(new int[] {2, 2}, new int[] {5, 1}, 300), rules);
        assertEquals(MatchVerdict.Status.WIN, v.status());
        assertEquals(0, v.winningTeam());
    }

    @Test
    void afterTimerTieIsDrawWithoutSuddenDeath() {
        MatchRules rules = new MatchRules(false, 0, 300, 0);
        MatchVerdict v = WinConditionResolver.resolve(snap(new int[] {2, 2}, new int[] {4, 4}, 305), rules);
        assertEquals(MatchVerdict.Status.DRAW, v.status());
    }

    @Test
    void afterTimerTieRunsOvertimeInsideWindow() {
        // Tied at the timer, still inside timer + suddenDeath -> overtime.
        MatchRules rules = new MatchRules(false, 0, 300, 60);
        MatchVerdict v = WinConditionResolver.resolve(snap(new int[] {2, 2}, new int[] {4, 4}, 320), rules);
        assertEquals(MatchVerdict.Status.CONTINUE, v.status());
    }

    @Test
    void afterTimerTiePastOvertimeWindowIsDraw() {
        // Still tied once the sudden-death window has also elapsed -> draw.
        MatchRules rules = new MatchRules(false, 0, 300, 60);
        MatchVerdict v = WinConditionResolver.resolve(snap(new int[] {2, 2}, new int[] {4, 4}, 360), rules);
        assertEquals(MatchVerdict.Status.DRAW, v.status());
    }

    @Test
    void overtimeBrokenByLeadResolvesToWin() {
        // During overtime a team pulls ahead at/after the timer -> it wins immediately.
        MatchRules rules = new MatchRules(false, 0, 300, 60);
        MatchVerdict v = WinConditionResolver.resolve(snap(new int[] {2, 2}, new int[] {6, 4}, 330), rules);
        assertEquals(MatchVerdict.Status.WIN, v.status());
        assertEquals(0, v.winningTeam());
    }

    @Test
    void zeroTimerWithTiedScoresIsImmediateDraw() {
        // timerSeconds 0 means regulation is already over; tied scores, no sudden death -> draw.
        MatchRules rules = new MatchRules(false, 0, 0, 0);
        MatchVerdict v = WinConditionResolver.resolve(snap(new int[] {2, 2}, new int[] {0, 0}, 0), rules);
        assertEquals(MatchVerdict.Status.DRAW, v.status());
    }

    // ----- PRECEDENCE: gate beats cap beats timer -----

    @Test
    void wipeGateBeatsScoreCap() {
        // Team 1 is at the cap, but team 0 is the lone survivor of a wipe -> elimination wins first.
        MatchRules rules = new MatchRules(true, 10, 300, 0);
        MatchVerdict v = WinConditionResolver.resolve(snap(new int[] {1, 0}, new int[] {2, 10}, 30), rules);
        assertEquals(MatchVerdict.Status.WIN, v.status());
        assertEquals(0, v.winningTeam());
    }

    @Test
    void scoreCapBeatsTimer() {
        // Cap reached well before the timer -> cap decides, not the (not-yet-expired) timer.
        MatchRules rules = new MatchRules(false, 10, 300, 0);
        MatchVerdict v = WinConditionResolver.resolve(snap(new int[] {2, 2}, new int[] {10, 0}, 50), rules);
        assertEquals(MatchVerdict.Status.WIN, v.status());
        assertEquals(0, v.winningTeam());
    }

    // ----- DEFENSIVE: empty / mismatched -----

    @Test
    void emptyRosterContinues() {
        MatchRules rules = new MatchRules(true, 10, 300, 0);
        MatchVerdict v = WinConditionResolver.resolve(snap(new int[] {}, new int[] {}, 500), rules);
        assertEquals(MatchVerdict.Status.CONTINUE, v.status());
    }
}
