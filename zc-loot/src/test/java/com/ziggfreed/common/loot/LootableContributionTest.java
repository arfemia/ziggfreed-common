package com.ziggfreed.common.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.factor.FactorFormula;
import com.ziggfreed.common.validation.Finding;

/**
 * One mod enriching another's table by id: what the enriched table ends up holding, whose decision
 * each part of it was, and what an author is told when the target does not exist.
 *
 * <p>The rule under all of it is that a contributor ADDS. It never replaces the target, never
 * changes how often the target's pool is drawn, and disappears cleanly if its pack is removed.
 */
class LootableContributionTest {

    @AfterEach
    void reset() {
        LootableConfig.getInstance().mergePackLayer(Map.of());
    }

    static void load(LootableAsset... tables) {
        Map<String, LootableAsset> layer = new LinkedHashMap<>();
        for (LootableAsset table : tables) {
            layer.put(table.getId(), table);
        }
        LootableConfig.getInstance().mergePackLayer(layer);
    }

    static Roll granting(String itemId) {
        return Roll.of(null, null, null, null, LootGrants.ofItem(itemId, 1), null);
    }

    static LootPool.Entry entry(String itemId, double weight) {
        return LootPool.Entry.of(weight, null, LootGrants.ofItem(itemId, 1));
    }

    static List<String> itemIdsOf(Roll[] rolls) {
        List<String> out = new ArrayList<>();
        for (Roll roll : rolls) {
            roll.getGrants().itemsOrEmpty().forEach(i -> out.add(i.getItem()));
        }
        return out;
    }

    // ==================== the fold ====================

    @Nested
    class TheEnrichedTable {

        @Test
        void aContributorsRollsRunAfterTheTargetsOwn() {
            load(LootableAsset.of("base", new Roll[] {granting("Staple")}),
                    LootableAsset.of("extra", new Roll[] {granting("Bonus")}, null, "base"));

            LootableAsset resolved = LootableConfig.getInstance().resolve("base");
            assertNotNull(resolved);
            assertEquals(List.of("Staple", "Bonus"), itemIdsOf(resolved.getRolls()));
        }

        @Test
        void aContributorsEntriesJoinTheTargetsBag() {
            load(LootableAsset.of("base", null,
                            LootPool.of(null, new LootPool.Entry[] {entry("Theirs", 5)}), null),
                    LootableAsset.of("extra", null,
                            LootPool.of(null, new LootPool.Entry[] {entry("Mine", 3)}), "base"));

            LootPool pool = LootableConfig.getInstance().resolve("base").getPool();
            assertNotNull(pool);
            assertEquals(2, pool.getEntries().length);
        }

        @Test
        void howOftenThePoolIsDrawnStaysTheTargetsDecision() {
            load(LootableAsset.of("base", null,
                            LootPool.of(picks(2), new LootPool.Entry[] {entry("Theirs", 5)}), null),
                    LootableAsset.of("extra", null,
                            LootPool.of(picks(9), new LootPool.Entry[] {entry("Mine", 3)}), "base"));

            LootPool pool = LootableConfig.getInstance().resolve("base").getPool();
            assertEquals(2, pool.pickCount(FactorLookup.none()),
                    "a contributor adds outcomes; it does not get to change the odds of drawing at all");
        }

        @Test
        void aTargetDeclaringNoPoolBorrowsTheFirstContributorsPickCount() {
            load(LootableAsset.of("base", new Roll[] {granting("Staple")}),
                    LootableAsset.of("extra", null,
                            LootPool.of(picks(3), new LootPool.Entry[] {entry("Mine", 1)}), "base"));

            LootPool pool = LootableConfig.getInstance().resolve("base").getPool();
            assertNotNull(pool, "a pool that exists only through contributions still has to be drawable");
            assertEquals(3, pool.pickCount(FactorLookup.none()));
        }

        @Test
        void contributorsFoldInAStableOrderWhateverOrderTheyLoadedIn() {
            load(LootableAsset.of("zzz", new Roll[] {granting("Last")}, null, "base"),
                    LootableAsset.of("aaa", new Roll[] {granting("First")}, null, "base"),
                    LootableAsset.of("base", new Roll[] {granting("Staple")}));

            assertEquals(List.of("Staple", "First", "Last"),
                    itemIdsOf(LootableConfig.getInstance().resolve("base").getRolls()));
        }

        @Test
        void aContributorIsStillAnOrdinaryTableInItsOwnRight() {
            load(LootableAsset.of("base", new Roll[] {granting("Staple")}),
                    LootableAsset.of("extra", new Roll[] {granting("Bonus")}, null, "base"));

            assertEquals(List.of("Bonus"),
                    itemIdsOf(LootableConfig.getInstance().resolve("extra").getRolls()));
        }

        @Test
        void removingTheContributingPackLeavesTheTargetExactlyAsItWas() {
            load(LootableAsset.of("base", new Roll[] {granting("Staple")}),
                    LootableAsset.of("extra", new Roll[] {granting("Bonus")}, null, "base"));
            load(LootableAsset.of("base", new Roll[] {granting("Staple")}));

            assertEquals(List.of("Staple"),
                    itemIdsOf(LootableConfig.getInstance().resolve("base").getRolls()));
        }

        @Test
        void theAuthoredViewIsWhatEachFileWroteRatherThanWhatItBecame() {
            load(LootableAsset.of("base", new Roll[] {granting("Staple")}),
                    LootableAsset.of("extra", new Roll[] {granting("Bonus")}, null, "base"));

            assertEquals(List.of("Staple"),
                    itemIdsOf(LootableConfig.getInstance().resolveAuthored("base").getRolls()));
        }

        @Test
        void aFileNamingItselfIsNotFoldedIntoItselfTwice() {
            load(LootableAsset.of("base", new Roll[] {granting("Staple")}, null, "base"));

            assertEquals(List.of("Staple"),
                    itemIdsOf(LootableConfig.getInstance().resolve("base").getRolls()));
        }

        @Test
        void aTargetNobodyShipsLeavesTheContributorPayingNothing() {
            load(LootableAsset.of("extra", new Roll[] {granting("Bonus")}, null, "absent_table"));

            assertNull(LootableConfig.getInstance().resolve("absent_table"));
            assertEquals(List.of("extra"), LootableConfig.getInstance().contributorsOf("absent_table"));
        }
    }

    // ==================== what the author is told ====================

    @Nested
    class TheFindings {

        @Test
        void aTargetNobodyShipsIsReported() {
            load(LootableAsset.of("extra", new Roll[] {granting("Bonus")}, null, "absent_table"));

            assertTrue(codes().contains(LootableValidator.UNKNOWN_CONTRIBUTION_TARGET));
        }

        @Test
        void aFileContributingToItselfIsReported() {
            load(LootableAsset.of("base", new Roll[] {granting("Staple")}, null, "BASE"));

            assertTrue(codes().contains(LootableValidator.SELF_CONTRIBUTION));
        }

        @Test
        void aPickCountThatWillBeIgnoredIsReported() {
            load(LootableAsset.of("base", null,
                            LootPool.of(picks(2), new LootPool.Entry[] {entry("Theirs", 1)}), null),
                    LootableAsset.of("extra", null,
                            LootPool.of(picks(9), new LootPool.Entry[] {entry("Mine", 1)}), "base"));

            assertTrue(codes().contains(LootableValidator.CONTRIBUTED_PICKS_IGNORED));
        }

        @Test
        void anOrdinaryPairOfTablesIsReportedClean() {
            load(LootableAsset.of("base", new Roll[] {granting("Staple")}),
                    LootableAsset.of("extra", new Roll[] {granting("Bonus")}, null, "base"));

            assertEquals(List.of(), codes());
        }

        static List<String> codes() {
            List<String> out = new ArrayList<>();
            for (Finding finding : LootableValidator.auditAll(null)) {
                out.add(finding.code());
            }
            return out;
        }
    }

    static FactorFormula picks(double base) {
        return FactorFormula.of(base, null, null);
    }
}
