package com.ziggfreed.common.instance.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * The score-tiered roll: determinism (seed in, same loot out), the always-granted guaranteed list, the
 * {@code minScore} eligibility gate, {@code ScorePerBonusRoll} scaling, the {@code MaxRolls} cap, and the
 * empty-eligible fallback. The roll powers "better loot for a better score".
 */
class LootTableTest {

    private static LootTable table(int rolls, int scorePerBonusRoll, int maxRolls, String[] guaranteed, String[] pool) {
        return new LootTable(LootEntry.parseAll(guaranteed), LootEntry.parseAll(pool),
                rolls, scorePerBonusRoll, maxRolls, "test", "test");
    }

    @Test
    void sameSeedSameLoot() {
        LootTable t = table(3, 0, 5, new String[]{"item Staple 1"},
                new String[]{"w10 item A 1-3", "w5 item B 2", "w2 item C 1-2"});
        List<InstanceReward> first = t.roll(2000, true, new Random(777));
        List<InstanceReward> second = t.roll(2000, true, new Random(777));
        assertEquals(first, second);
    }

    @Test
    void guaranteedGrantedOnWinEvenWithEmptyPool() {
        LootTable t = table(3, 0, 5, new String[]{"item Staple 1", "item Other 2"}, new String[]{});
        List<InstanceReward> out = t.roll(99999, true, new Random(1));
        assertEquals(2, out.size());
        assertEquals("Staple", out.get(0).id());
        assertEquals("Other", out.get(1).id());
    }

    @Test
    void scoreGateExcludesPremiumBelowThreshold() {
        // Premium entry gated at s1000 with overwhelming weight; below the gate it can never appear.
        LootTable t = table(4, 0, 4, new String[]{},
                new String[]{"w1 item Common 1", "w1000 s1000 item Premium 1"});
        List<InstanceReward> low = t.roll(500, true, new Random(42));
        assertFalse(low.stream().anyMatch(r -> r.id().equals("Premium")), "premium must be gated out at low score");
        assertTrue(low.stream().allMatch(r -> r.id().equals("Common")));
    }

    @Test
    void scoreGateAdmitsPremiumAtThreshold() {
        LootTable t = table(4, 0, 4, new String[]{},
                new String[]{"w1 item Common 1", "w1000 s1000 item Premium 1"});
        List<InstanceReward> high = t.roll(1500, true, new Random(42));
        assertTrue(high.stream().anyMatch(r -> r.id().equals("Premium")), "premium must drop once eligible + weighted");
    }

    @Test
    void scorePerBonusRollScalesPickCount() {
        // One entry, fixed qty 1 -> pick count == out.size() (no guaranteed).
        LootTable t = table(1, 1000, 5, new String[]{}, new String[]{"item A 1"});
        assertEquals(1, t.roll(0, true, new Random(1)).size());      // base only
        assertEquals(4, t.roll(3000, true, new Random(1)).size());   // 1 + 3000/1000
        assertEquals(5, t.roll(99999, true, new Random(1)).size());  // capped at MaxRolls
    }

    @Test
    void eligibleEmptyReturnsGuaranteedOnly() {
        LootTable t = table(3, 0, 5, new String[]{"item Staple 1"}, new String[]{"s1000 item Premium 1"});
        List<InstanceReward> out = t.roll(0, true, new Random(5));
        assertEquals(1, out.size());
        assertEquals("Staple", out.get(0).id());
    }

    @Test
    void emptyTableYieldsNothing() {
        LootTable t = table(3, 0, 5, new String[]{}, new String[]{});
        assertTrue(t.roll(5000, true, new Random(9)).isEmpty());
    }

    @Test
    void defaultGateIsWinOnlySoLossYieldsNothing() {
        // Un-annotated entries default to WIN; a loss roll drops guaranteed AND pool (preserves the
        // historical whole-table ON_WIN behaviour with zero content annotation).
        LootTable t = table(3, 0, 5, new String[]{"item Staple 1"}, new String[]{"w10 item A 1-3"});
        assertTrue(t.roll(5000, false, new Random(3)).isEmpty(), "default WIN entries must not pay on a loss");
    }

    @Test
    void lossGatedGuaranteedPaysOnLossOnly() {
        // A participation entry (gate loss) pays on a loss; the win-default staple does not.
        LootTable t = table(3, 0, 5, new String[]{"item Staple 1", "loss item Consolation 1"}, new String[]{});
        List<InstanceReward> loss = t.roll(0, false, new Random(1));
        assertEquals(1, loss.size());
        assertEquals("Consolation", loss.get(0).id());
        List<InstanceReward> win = t.roll(0, true, new Random(1));
        assertEquals(1, win.size());
        assertEquals("Staple", win.get(0).id());
    }

    @Test
    void anyGatedEntryPaysBothOutcomes() {
        LootTable t = table(3, 0, 5, new String[]{"any item Always 1"}, new String[]{});
        assertEquals("Always", t.roll(0, true, new Random(1)).get(0).id());
        assertEquals("Always", t.roll(0, false, new Random(1)).get(0).id());
    }
}
