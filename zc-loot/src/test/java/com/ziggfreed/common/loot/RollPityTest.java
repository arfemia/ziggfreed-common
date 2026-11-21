package com.ziggfreed.common.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.schema.SchemaContext;
import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.factor.FactorFormula;
import com.ziggfreed.common.loot.pity.PityLedger;

/**
 * A roll's {@code Pity} group decodes and shows in the editor, grows the chance by its step per miss up to its
 * cap and never below the plain chance, and its count steps on a Claim the roll missed in reach and starts over on
 * one that holds it. Mechanics only: no number here is a balance.
 */
class RollPityTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @Test
    void theGroupDecodesItsLeavesAndARollWithoutItReadsNone() throws Exception {
        LootableAsset table = LootableAssetCodecTest.decodeRoot("""
                { "Rolls": [
                    { "Chance": { "Base": 4 }, "Pity": { "Id": "Rare_Find", "Step": 0.5, "Max": 20 },
                      "Grants": { "Items": [ { "Item": "Gem_Ruby" } ] } },
                    { "Chance": { "Base": 4 }, "Pity": { "Id": "Other" },
                      "Grants": { "Items": [ { "Item": "Gem_Ruby" } ] } },
                    { "Grants": { "Items": [ { "Item": "Coin_Gold" } ] } } ] }
                """, "pity_finds");

        Roll.Pity pity = table.getRolls()[0].getPity();
        assertNotNull(pity);
        assertEquals("Rare_Find", pity.getId());
        assertEquals("rare_find", pity.key(), "the count is keyed in one case");
        assertEquals(0.5, pity.effectiveStep(), 1e-9);
        assertEquals(20.0, pity.effectiveMax(), 1e-9);

        Roll.Pity bare = table.getRolls()[1].getPity();
        assertEquals(0.0, bare.effectiveStep(), 1e-9, "an unwritten Step never grows");
        assertEquals(Roll.Pity.DEFAULT_MAX_PERCENT, bare.effectiveMax(), 1e-9, "an unwritten Max grows up to 100");

        assertNull(table.getRolls()[2].getPity());
    }

    @Test
    void theEditorListsTheGroupAndItsLeaves() {
        assertTrue(Roll.CODEC.toSchema(new SchemaContext()).getProperties().containsKey("Pity"));
        assertNotNull(Roll.Pity.CODEC.toSchema(new SchemaContext()).getProperties().get("Step")
                .getMarkdownDescription());
    }

    @Test
    void theChanceGrowsByItsStepUpToItsCapAndNeverBelowThePlainChance() {
        Roll.Pity pity = Roll.Pity.of("Rare_Find", 0.5, 20.0);
        assertEquals(4.0, pity.grown(4.0, 0), 1e-9);
        assertEquals(9.0, pity.grown(4.0, 10), 1e-9);
        assertEquals(20.0, pity.grown(4.0, 1000), 1e-9, "held at Max");
        assertEquals(30.0, pity.grown(30.0, 5), 1e-9, "Max never pulls a higher chance down");
        assertEquals(4.0, Roll.Pity.of(" ", 0.5, null).grown(4.0, 10), 1e-9, "a blank Id is off");

        Roll roll = Roll.of(null, null, FactorFormula.of(4.0, null, null), null, LootGrants.ofItem("Gem_Ruby", 1), null)
                .withPity(pity);
        FactorLookup tenMisses = FactorLookup.withPityMisses(FactorLookup.none(), key -> "rare_find".equals(key) ? 10 : 0);
        assertEquals(9.0, RollEvaluator.effectiveChancePercent(roll, tenMisses), 1e-9);
        assertEquals(4.0, RollEvaluator.effectiveChancePercent(roll, FactorLookup.none()), 1e-9,
                "a lookup with no misses reads the plain odds");
        assertEquals(4.0, RollEvaluator.effectiveChancePercent(roll.getChance(), tenMisses), 1e-9,
                "the formula's own reading, a preview's, never grows");
    }

    @Test
    void aMissInReachStepsTheCountAndAPickHeldStartsItOver() {
        PityLedger ledger = new PityLedger("pity-test");
        Roll rare = Roll.of(null, null, FactorFormula.of(4.0, null, null), null, LootGrants.ofItem("Gem_Ruby", 1), null)
                .withPity(Roll.Pity.of("Rare_Find", 0.5, null));
        Roll deep = Roll.of(null, new FactorCondition[] {FactorCondition.of("test:depth", null, 3.0, null)},
                FactorFormula.of(4.0, null, null), null, LootGrants.ofItem("Gem_Ruby", 1), null)
                .withPity(Roll.Pity.of("Deep_Find", 0.5, null));
        FactorLookup depthOne = (factor, param) -> "test:depth".equals(factor) ? 1.0 : null;

        ledger.settle(PLAYER, List.of(rare, deep), List.of(), depthOne);
        ledger.settle(PLAYER, List.of(rare, deep), List.of(), depthOne);
        assertEquals(2, ledger.misses(PLAYER, "RARE_FIND"), "two Claims missed it, read in any case");
        assertEquals(0, ledger.misses(PLAYER, "Deep_Find"), "a roll its conditions shut out never misses");

        LootEngine.Selected held = new LootEngine.Selected(rare.getGrants(), null, rare, LootEngine.Origin.ROLL);
        ledger.settle(PLAYER, List.of(rare, deep), List.of(held), depthOne);
        assertEquals(0, ledger.misses(PLAYER, "Rare_Find"), "a Claim holding it starts the count over");

        PityLedger.Tally passes = new PityLedger.Tally();
        passes.add(List.of(rare), List.of(), depthOne);
        passes.add(List.of(rare), List.of(), depthOne);
        ledger.settle(PLAYER, passes);
        assertEquals(1, ledger.misses(PLAYER, "Rare_Find"), "one Claim is one miss, however many passes it rolled");
    }
}
