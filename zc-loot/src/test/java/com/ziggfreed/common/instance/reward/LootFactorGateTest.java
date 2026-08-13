package com.ziggfreed.common.instance.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.loot.FactorGate;
import com.ziggfreed.common.loot.FactorLookup;
import com.ziggfreed.common.loot.LootFactors;

/**
 * What "unlocks at 4000 points" and "only on a win" mean once they are ordinary factor conditions.
 *
 * <p>These are the answers every score-gated payout rests on, wherever it is authored: a compact
 * {@link LootEntry} spec, a pool entry's {@code Conditions}, or a chance formula's terms. They are
 * pinned here rather than beside any one of those, because all three ask the same question of the
 * same two readings.
 */
class LootFactorGateTest {

    static LootEntry entry(String id, int minScore, WinGate gate) {
        return new LootEntry(InstanceReward.Kind.ITEM, id, 1, 1, 1, minScore, null, null, gate);
    }

    @Test
    void anUnannotatedEntryStaysWinOnly() {
        LootEntry plain = entry("Spoils", 0, null);
        assertEquals(WinGate.WIN, plain.gate(), "an absent gate must keep meaning win-only");
        assertTrue(FactorGate.pass(plain.conditions(), LootFactors.lookupFor(0, true)));
        assertFalse(FactorGate.pass(plain.conditions(), LootFactors.lookupFor(0, false)));
    }

    @Test
    void aGuaranteedEntryIgnoresTheScoreButNotTheOutcome() {
        LootEntry gated = entry("WinOnlyBaseline", 9999, WinGate.WIN);
        assertTrue(FactorGate.pass(gated.gateConditions(), LootFactors.lookupFor(0, true)),
                "a guaranteed entry is guaranteed; the score gates the pool, not the baseline");
        assertFalse(FactorGate.pass(gated.gateConditions(), LootFactors.lookupFor(99999, false)));
    }

    @Test
    void theTwoReadingsAreUnanswerableWithNoOutcomeSoAGateOverThemFailsClosed() {
        LootEntry e = entry("Spoils", 100, WinGate.WIN);
        assertFalse(FactorGate.pass(e.conditions(), FactorLookup.none()),
                "asked outside a run, 'did they win?' must not answer yes");
    }

    @Test
    void aNegativeScoreReadsAsZero() {
        assertEquals(0.0, LootFactors.lookupFor(-500, true).resolve(LootFactors.INSTANCE_SCORE, null));
    }

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
