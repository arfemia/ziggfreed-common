package com.ziggfreed.common.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorRegistry;
import com.ziggfreed.common.factor.ModGates;

/**
 * A loot table honours its whole {@code Requires} where it pays: every condition but the plain mod-presence
 * gate (the fold's) goes in front of each roll and pool entry the table pays, through the moment's own
 * vocabulary. The enriched table keeps the base file's block through both rebuilds, a contributor's file and
 * a roll source included, which is the rebuild that drops leaves and has bitten before. A table asking only
 * for mod presence pays its authored instances untouched.
 *
 * <p>Fixture table, owner and item ids are this test's own.
 */
class LootableRequiresTest {

    private static final String RANK = "yourmod:rank";
    private static final String TABLE = "Yourmod_Finds";
    private static final String SOURCE_OWNER = "fixture_rank_source";
    private static final String GATED = "{ \"Requires\": { \"Factors\": ["
            + " { \"Factor\": \"hytale:mod_installed\", \"Param\": \"Yourgroup:Yourmod\", \"Min\": 1 },"
            + " { \"Factor\": \"" + RANK + "\", \"Min\": 5 } ] },"
            + " \"Rolls\": [ { \"Grants\": { \"Items\": [ { \"Item\": \"Coin_Gold\" } ] } } ],"
            + " \"Pool\": { \"Entries\": [ { \"Grants\": { \"Items\": [ { \"Item\": \"Gem_Ruby\" } ] } } ] } }";

    @AfterEach
    void tearDown() {
        LootableConfig.getInstance().unregisterRollSource(SOURCE_OWNER);
        LootableConfig.getInstance().mergePackLayer(Map.of());
        ModGates.useProbeForTests(null);
    }

    private static LootableAsset table(String json) throws IOException {
        return LootableAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), null,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(LootableAsset.class, TABLE, null)));
    }

    /** A moment whose own vocabulary reads the player's rank as {@code rank}, and nothing else. */
    private static FactorSnapshot playerAt(double rank) {
        FactorRegistry loot = new FactorRegistry("loot-requires-test");
        loot.register(RANK, ctx -> rank);
        return new FactorSnapshot(loot, FactorContext.builder().build());
    }

    private static boolean hits(Roll roll, FactorLookup lookup) {
        return RollEvaluator.evaluate(roll, lookup, () -> 0.0).isHit();
    }

    private static List<LootPool.Entry> entriesOf(List<LootPool> pools) {
        List<LootPool.Entry> out = new ArrayList<>();
        for (LootPool pool : pools) {
            for (LootPool.Entry entry : pool.getEntries()) {
                out.add(entry);
            }
        }
        return out;
    }

    @Test
    void aTableGatedOnRankFivePaysAtSevenAndRefusesAtThree() throws IOException {
        LootableAsset gated = table(GATED);
        Roll roll = gated.payingRolls().get(0);
        LootPool.Entry entry = gated.payingPool().get(0).getEntries()[0];

        assertTrue(hits(roll, playerAt(7)), "rank 7 meets Min 5, and the mod-presence gate is the fold's");
        assertFalse(hits(roll, playerAt(3)), "rank 3 is refused the table's roll");
        assertTrue(entry.isEligible(playerAt(7)));
        assertFalse(entry.isEligible(playerAt(3)), "and kept out of the table's bag");
        assertFalse(hits(roll, FactorLookup.none()), "a moment with nobody to ask refuses a gated table");
        assertSame(gated.payingRolls(), gated.payingRolls(), "the gated copies are made once per table");
    }

    @Test
    void aContributorAndARollSourceStillRefuseAtThree() throws IOException {
        LootableAsset contributor = LootableAsset.of("yourmod_extra",
                new Roll[] {LootableContributionTest.granting("Bonus")},
                LootPool.of(null, new LootPool.Entry[] {LootableContributionTest.entry("Pick", 1)}), TABLE);
        LootableContributionTest.load(table(GATED), contributor);
        LootableConfig.getInstance().registerRollSource(SOURCE_OWNER, id -> id.equalsIgnoreCase(TABLE)
                ? List.of(LootableContributionTest.granting("Sourced")) : List.of());

        LootEngine.Resolved resolved = LootEngine.resolve(LootRef.of(new String[] {TABLE}, null), null);
        List<Roll> rolls = resolved.rolls();
        List<LootPool.Entry> entries = entriesOf(resolved.pools());

        assertEquals(List.of("Coin_Gold", "Bonus", "Sourced"),
                LootableContributionTest.itemIdsOf(rolls.toArray(Roll[]::new)),
                "the table's own roll, the contributor's and the source's all pay through it");
        assertEquals(2, entries.size(), "the table's entry and the contributor's share one bag");
        for (Roll roll : rolls) {
            assertTrue(hits(roll, playerAt(7)));
            assertFalse(hits(roll, playerAt(3)), "every roll the enriched table pays asks its Requires");
        }
        for (LootPool.Entry entry : entries) {
            assertTrue(entry.isEligible(playerAt(7)));
            assertFalse(entry.isEligible(playerAt(3)), "and every entry in its bag");
        }
    }

    @Test
    void aPresenceOnlyTablePaysItsAuthoredInstances() throws IOException {
        ModGates.useProbeForTests(param -> 1.0);
        LootableAsset table = table("{ \"Requires\": { \"Factors\": [ { \"Factor\": \"hytale:mod_installed\","
                + " \"Param\": \"Yourgroup:Yourmod\", \"Min\": 1 } ] },"
                + " \"Rolls\": [ { \"Grants\": { \"Items\": [ { \"Item\": \"Coin_Gold\" } ] } } ],"
                + " \"Pool\": { \"Entries\": [ { \"Grants\": { \"Items\": [ { \"Item\": \"Gem_Ruby\" } ] } } ] } }");

        assertSame(table.rollsOrEmpty().get(0), table.payingRolls().get(0), "nothing left to ask, so no copy");
        assertSame(table.getPool(), table.payingPool().get(0));
        assertTrue(hits(table.payingRolls().get(0), FactorLookup.none()));
        assertTrue(table.payingPool().get(0).getEntries()[0].isEligible(FactorLookup.none()));
    }
}
