package com.ziggfreed.common.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.ziggfreed.common.progress.gate.GateRuntime;

/**
 * A loot table reads its whole {@code Requires} (R606): what is not plain mod presence is asked of the
 * moment's player before any roll or pool entry of the table pays, and a lookup that can put no question
 * refuses it. The enriched table keeps the base file's block through both rebuilds, a contributor's file and a
 * roll source included, which is the rebuild that drops leaves and has bitten before. A table asking only for
 * mod presence pays its authored rolls as they are.
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
        GateRuntime.resetForTests();
        ModGates.useProbeForTests(null);
    }

    private static LootableAsset table(String json) throws IOException {
        return LootableAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), null,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(LootableAsset.class, TABLE, null)));
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

    /** A moment whose own vocabulary reads the player's rank as {@code rank}. */
    private static FactorSnapshot playerAt(double rank) {
        FactorRegistry loot = new FactorRegistry("loot-test");
        loot.register(RANK, ctx -> rank);
        return new FactorSnapshot(loot, FactorContext.builder().build());
    }

    @Test
    void theTablesOwnConditionsDecideWhoItPays() throws IOException {
        LootableAsset table = table(GATED);
        Roll roll = table.payingRolls().get(0);
        LootPool.Entry entry = table.payingPool().get(0).getEntries()[0];

        assertTrue(RollEvaluator.evaluate(roll, playerAt(7), () -> 0.0).isHit());
        assertFalse(RollEvaluator.evaluate(roll, playerAt(3), () -> 0.0).isHit(),
                "a rank the table asks more than refuses its roll, where it used to pay");
        assertTrue(entry.isEligible(playerAt(7)));
        assertFalse(entry.isEligible(playerAt(3)), "and keeps its pool entries out of the bag");
        assertFalse(RollEvaluator.evaluate(roll, FactorLookup.none(), () -> 0.0).isHit(),
                "a lookup with nobody to ask refuses a gated table");
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
    void aTableAskingOnlyForModPresencePaysItsRollsAsAuthored() throws IOException {
        ModGates.useProbeForTests(param -> 1.0);
        LootableAsset table = table("{ \"Requires\": { \"Factors\": [ { \"Factor\": \"hytale:mod_installed\","
                + " \"Param\": \"Yourgroup:Yourmod\", \"Min\": 1 } ] },"
                + " \"Rolls\": [ { \"Grants\": { \"Items\": [ { \"Item\": \"Coin_Gold\" } ] } } ],"
                + " \"Pool\": { \"Entries\": [ { \"Grants\": { \"Items\": [ { \"Item\": \"Gem_Ruby\" } ] } } ] } }");

        assertNull(table.liveRequires(), "the fold decided presence; nothing is left to ask");
        assertSame(table.rollsOrEmpty().get(0), table.payingRolls().get(0));
        assertEquals(0, table.payingRolls().get(0).gates().length);
        assertTrue(RollEvaluator.evaluate(table.payingRolls().get(0), FactorLookup.none(), () -> 0.0).isHit());
        assertSame(table.getPool(), table.payingPool().get(0), "nothing left to ask, so no copy of the pool");
        assertTrue(table.payingPool().get(0).getEntries()[0].isEligible(FactorLookup.none()));
    }

    @Test
    void theGatedCopiesAreMadeOncePerTable() throws IOException {
        LootableAsset table = table(GATED);
        List<Roll> first = table.payingRolls();

        assertSame(first, table.payingRolls(), "a hot table pays no copy per pass");
        assertEquals(1, first.get(0).gates().length);
    }
}
