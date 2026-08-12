package com.ziggfreed.common.instance.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.loot.FactorGate;
import com.ziggfreed.common.loot.FactorLookup;
import com.ziggfreed.common.loot.LootFactors;

/**
 * The score-tiered table now expresses "unlocks at 4000 points" and "only on a win" as ordinary
 * factor conditions, and picks through the one weighted-pick primitive.
 *
 * <p>Which means the thing to prove is that NOTHING a caller can see changed. These cases pin the
 * exact picks for fixed seeds, so a table rolled today hands over what it handed over before the
 * eligibility rules stopped being bespoke fields.
 */
class LootTableRebaseTest {

    static LootEntry entry(String id, int weight, int minScore, WinGate gate) {
        return new LootEntry(InstanceReward.Kind.ITEM, id, 1, 1, weight, minScore, null, null, gate);
    }

    static LootTable table(List<LootEntry> guaranteed, List<LootEntry> pool, int rolls,
            int scorePerBonusRoll, int maxRolls) {
        return new LootTable(guaranteed, pool, rolls, scorePerBonusRoll, maxRolls, "t", "t", null);
    }

    static List<String> ids(List<InstanceReward> rewards) {
        List<String> out = new ArrayList<>();
        rewards.forEach(r -> out.add(r.id()));
        return out;
    }

    /** A three-tier table: common always, premium at 2000, elite at 8000, plus a loss consolation. */
    static LootTable scoreTiered() {
        return table(
                List.of(entry("Participation", 1, 0, WinGate.ANY)),
                List.of(entry("Common", 60, 0, WinGate.WIN),
                        entry("Premium", 30, 2000, WinGate.WIN),
                        entry("Elite", 10, 8000, WinGate.WIN),
                        entry("Consolation", 50, 0, WinGate.LOSS)),
                1, 2000, 4);
    }

    // ==================== eligibility, now as conditions ====================

    @Nested
    class Eligibility {

        @Test
        void scoreUnlocksTiersExactlyAsBefore() {
            LootTable t = scoreTiered();
            assertFalse(ids(t.roll(0, true, new Random(1))).contains("Premium"));
            assertFalse(ids(t.roll(1999, true, new Random(1))).contains("Elite"));
            assertTrue(eligibleIds(t, 8000, true).containsAll(List.of("Common", "Premium", "Elite")));
        }

        @Test
        void anUnannotatedEntryStaysWinOnly() {
            LootEntry plain = entry("Spoils", 1, 0, null);
            assertEquals(WinGate.WIN, plain.gate(), "an absent gate must keep meaning win-only");
            assertTrue(FactorGate.pass(plain.conditions(), LootFactors.lookupFor(0, true)));
            assertFalse(FactorGate.pass(plain.conditions(), LootFactors.lookupFor(0, false)));
        }

        @Test
        void aLossPaysOnlyTheLossAndAnyEntries() {
            List<String> lost = ids(scoreTiered().roll(9000, false, new Random(7)));
            assertTrue(lost.contains("Participation"));
            assertFalse(lost.contains("Common"));
            assertFalse(lost.contains("Elite"));
            assertTrue(lost.contains("Consolation"));
        }

        @Test
        void aGuaranteedEntryIgnoresTheScoreButNotTheOutcome() {
            LootEntry gated = entry("WinOnlyBaseline", 1, 9999, WinGate.WIN);
            assertTrue(FactorGate.pass(gated.gateConditions(), LootFactors.lookupFor(0, true)),
                    "a guaranteed entry is guaranteed; the score gates the pool, not the baseline");
            assertFalse(FactorGate.pass(gated.gateConditions(), LootFactors.lookupFor(99999, false)));
        }

        @Test
        void theTwoReadingsAreUnanswerableWithNoOutcomeSoAGateOverThemFailsClosed() {
            LootEntry e = entry("Spoils", 1, 100, WinGate.WIN);
            assertFalse(FactorGate.pass(e.conditions(), FactorLookup.none()),
                    "asked outside a run, 'did they win?' must not answer yes");
        }

        @Test
        void aNegativeScoreReadsAsZero() {
            assertEquals(0.0, LootFactors.lookupFor(-500, true).resolve(LootFactors.INSTANCE_SCORE, null));
        }

        static List<String> eligibleIds(LootTable table, int score, boolean win) {
            FactorLookup lookup = LootFactors.lookupFor(score, win);
            List<String> out = new ArrayList<>();
            for (LootEntry e : table.pool()) {
                if (FactorGate.pass(e.conditions(), lookup)) {
                    out.add(e.id());
                }
            }
            return out;
        }
    }

    // ==================== bonus rolls ====================

    @Nested
    class RollCount {

        @Test
        void scoreEarnsBonusRollsUpToTheCap() {
            LootTable t = scoreTiered();
            assertEquals(1, t.rollCount(0));
            assertEquals(2, t.rollCount(2000));
            assertEquals(4, t.rollCount(6000));
            assertEquals(4, t.rollCount(1_000_000), "maxRolls is the ceiling, however good the run");
        }

        @Test
        void aTableWithNoBonusRuleAlwaysRollsItsBase() {
            LootTable flat = table(List.of(), List.of(entry("Common", 1, 0, WinGate.ANY)), 3, 0, 0);
            assertEquals(3, flat.rollCount(0));
            assertEquals(3, flat.rollCount(50_000));
        }

        @Test
        void theRollCountIsHonouredByTheRollItself() {
            LootTable flat = table(List.of(), List.of(entry("Common", 1, 0, WinGate.ANY)), 3, 0, 0);
            assertEquals(3, flat.roll(0, true, new Random(5)).size());
        }
    }

    // ==================== the parity anchor ====================

    @Nested
    class Determinism {

        @Test
        void aFixedSeedProducesAFixedResult() {
            LootTable t = scoreTiered();
            List<String> first = ids(t.roll(5000, true, new Random(20260811L)));
            List<String> second = ids(t.roll(5000, true, new Random(20260811L)));
            assertEquals(first, second);
        }

        @Test
        void thePinnedRunIsWhatTheTableHasAlwaysHandedOver() {
            // Pinned against the pre-rebase algorithm: cumulative-subtract weighted pick over the
            // eligible set, one nextDouble per pick, quantities rolled from the same stream. A change
            // here means the payout a player receives changed, not merely that the code moved.
            assertEquals(List.of("Participation", "Common", "Common", "Common"),
                    ids(scoreTiered().roll(5000, true, new Random(20260811L))));
        }

        @Test
        void aDifferentSeedGivesADifferentRun() {
            LootTable t = scoreTiered();
            List<String> a = ids(t.roll(5000, true, new Random(1)));
            List<String> b = ids(t.roll(5000, true, new Random(987654321L)));
            List<String> c = ids(t.roll(5000, true, new Random(42)));
            assertFalse(a.equals(b) && a.equals(c),
                    "three seeds all reproducing one run would mean the stream is not being consumed");
        }
    }

    // ==================== authored condition shapes ====================

    @Test
    void theAuthoredConditionShapesSayWhatTheyMean() {
        FactorCondition win = LootFactors.onWin();
        assertEquals(LootFactors.INSTANCE_WIN, win.getFactor());
        assertTrue(win.accepts(1.0));
        assertFalse(win.accepts(0.0));

        FactorCondition loss = LootFactors.onLoss();
        assertTrue(loss.accepts(0.0));
        assertFalse(loss.accepts(1.0));

        FactorCondition score = LootFactors.atLeastScore(2000);
        assertTrue(score.accepts(2000.0));
        assertFalse(score.accepts(1999.0));
        assertFalse(score.accepts(null), "an unanswerable reading never opens a gate");
    }
}
