package com.ziggfreed.common.encounter.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * The ledger's contract, pure: three independent counters keyed by run AND player, shares against
 * the top contributor, the minimum cut, the dead-member rule, and the drop.
 */
class ParticipationLedgerTest {

    private static final UUID RUN = UUID.randomUUID();
    private static final UUID OTHER_RUN = UUID.randomUUID();
    private static final UUID TANK = UUID.randomUUID();
    private static final UUID DPS = UUID.randomUUID();
    private static final UUID BYSTANDER = UUID.randomUUID();

    private static final ParticipationWeights WEIGHTS = new ParticipationWeights(1.0, 0.25, 0.1);

    @Test
    void theTopContributorReadsOneAndTheRestAFractionOfIt() {
        ParticipationLedger ledger = new ParticipationLedger();
        ledger.creditDamageDealt(RUN, DPS, "dps", 400.0);
        ledger.creditDamageDealt(RUN, TANK, "tank", 100.0);
        ledger.creditDamageTaken(RUN, TANK, "tank", 400.0);
        ParticipationShares shares = ledger.shares(RUN, 0.05, true, id -> WEIGHTS);
        assertEquals(List.of(DPS, TANK), shares.participantIds());
        assertEquals(1.0, shares.shares().get(DPS), 1e-9);
        // tank: 100 dealt + 400 taken x 0.25 = 200, against the top's 400
        assertEquals(0.5, shares.shares().get(TANK), 1e-9);
        assertTrue(shares.participants().get(1).credited());
    }

    @Test
    void aShareUnderTheMinimumIsAttemptCreditOnly() {
        ParticipationLedger ledger = new ParticipationLedger();
        ledger.creditDamageDealt(RUN, DPS, "dps", 1000.0);
        ledger.creditPresence(RUN, BYSTANDER, "afk", 10.0);
        ParticipationShares shares = ledger.shares(RUN, 0.05, true, id -> WEIGHTS);
        ParticipantShare afk = shares.participants().get(1);
        assertEquals(BYSTANDER, afk.playerId());
        assertEquals(0.001, afk.share(), 1e-9);
        assertFalse(afk.credited());
        assertEquals(0.0, afk.lootShare(), 0.0);
        assertEquals(1, shares.credited().size());
        assertEquals(2, shares.size(), "the attempt is still recorded");
    }

    @Test
    void aDeadMemberKeepsTheirShareOnlyWhenTheRuleCreditsTheDead() {
        ParticipationLedger ledger = new ParticipationLedger();
        ledger.creditDamageDealt(RUN, DPS, "dps", 300.0);
        ledger.creditDamageDealt(RUN, TANK, "tank", 300.0);
        ledger.recordDeath(RUN, TANK, "tank");
        ParticipationShares credited = ledger.shares(RUN, 0.05, true, id -> WEIGHTS);
        assertEquals(1.0, credited.shares().get(TANK), 1e-9);
        ParticipationShares notCredited = ledger.shares(RUN, 0.05, false, id -> WEIGHTS);
        assertEquals(0.0, notCredited.shares().get(TANK), 0.0);
        assertFalse(notCredited.participants().get(1).credited());
        assertTrue(notCredited.participants().get(1).died());
        assertEquals(1, ledger.deaths(RUN));
    }

    @Test
    void creditNeverLeaksBetweenRuns() {
        ParticipationLedger ledger = new ParticipationLedger();
        ledger.creditDamageDealt(RUN, DPS, "dps", 100.0);
        ledger.creditDamageDealt(OTHER_RUN, DPS, "dps", 5.0);
        assertEquals(100.0, ledger.shares(RUN, 0.0, true, id -> WEIGHTS).damageDealt().get(DPS), 1e-9);
        assertEquals(5.0, ledger.shares(OTHER_RUN, 0.0, true, id -> WEIGHTS).damageDealt().get(DPS), 1e-9);
        ledger.drop(RUN);
        assertTrue(ledger.isEmpty(RUN));
        assertFalse(ledger.isEmpty(OTHER_RUN));
    }

    @Test
    void weightsAreAskedPerParticipantAndANegativeOneWeighsNothing() {
        ParticipationLedger ledger = new ParticipationLedger();
        ledger.creditDamageDealt(RUN, DPS, "dps", 100.0);
        ledger.creditDamageDealt(RUN, TANK, "tank", 100.0);
        ParticipationShares shares = ledger.shares(RUN, 0.0, true,
                id -> id.equals(TANK) ? new ParticipationWeights(-1.0, 0.0, 0.0) : WEIGHTS);
        assertEquals(1.0, shares.shares().get(DPS), 1e-9);
        assertEquals(0.0, shares.shares().get(TANK), 0.0);
    }

    @Test
    void nothingCreditedMeansAnEmptyAnswer() {
        ParticipationLedger ledger = new ParticipationLedger();
        assertTrue(ledger.shares(RUN, 0.05, true, id -> WEIGHTS).isEmpty());
        ledger.creditDamageDealt(RUN, DPS, "dps", 0.0);
        assertTrue(ledger.shares(RUN, 0.05, true, id -> WEIGHTS).isEmpty(), "a zero credit records nobody");
    }

    @Test
    void theLastSeenNameWins() {
        ParticipationLedger ledger = new ParticipationLedger();
        ledger.creditDamageDealt(RUN, DPS, null, 10.0);
        ledger.creditPresence(RUN, DPS, "Renamed", 1.0);
        assertEquals("Renamed", ledger.shares(RUN, 0.0, true, id -> WEIGHTS).participants().get(0).playerName());
    }
}
