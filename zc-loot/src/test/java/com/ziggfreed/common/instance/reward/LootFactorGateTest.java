package com.ziggfreed.common.instance.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.loot.LootFactors;

/**
 * What "unlocks at 4000 points" and "only on a win" mean once they are ordinary factor conditions.
 *
 * <p>These are the answers every score-gated payout rests on, wherever it is authored: a pool entry's
 * {@code Conditions} or a chance formula's terms. They are pinned here rather than beside either of
 * those, because both ask the same question of the same two readings.
 */
class LootFactorGateTest {

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
        assertFalse(win.accepts(null), "asked outside a run, \"did they win?\" never answers yes");

        FactorCondition loss = LootFactors.onLoss();
        assertTrue(loss.accepts(0.0));
        assertFalse(loss.accepts(1.0));
        assertFalse(loss.accepts(null), "asked outside a run, \"did they lose?\" never answers yes");

        FactorCondition score = LootFactors.atLeastScore(2000);
        assertTrue(score.accepts(2000.0));
        assertFalse(score.accepts(1999.0));
        assertFalse(score.accepts(null), "an unanswerable reading never opens a gate");
    }
}
