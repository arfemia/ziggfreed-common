package com.ziggfreed.common.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A running mod adding rolls to a table through a {@link LootableConfig.RollSource}: where its rolls
 * land among the file contributions, that every reader of the one table resolve sees them, and that
 * a source going away or breaking costs nothing but its own rolls.
 *
 * <p>Fixture owner, table and item ids are this test's own.
 */
class LootableRollSourceTest {

    private static final String OWNER_A = "fixture_owner_a";
    private static final String OWNER_Z = "fixture_owner_z";

    private final List<String> warnings = new ArrayList<>();

    @AfterEach
    void reset() {
        LootableConfig config = LootableConfig.getInstance();
        config.unregisterRollSource(OWNER_A);
        config.unregisterRollSource(OWNER_Z);
        config.warnInto(null);
        config.mergePackLayer(Map.of());
    }

    private static Roll granting(String itemId) {
        return LootableContributionTest.granting(itemId);
    }

    private static List<String> itemIdsOf(List<Roll> rolls) {
        return LootableContributionTest.itemIdsOf(rolls.toArray(Roll[]::new));
    }

    /** A source answering {@code itemId} for table {@code tableId} only. */
    private static LootableConfig.RollSource onlyFor(String tableId, String itemId) {
        return id -> id.equals(tableId) ? List.of(granting(itemId)) : List.of();
    }

    @Test
    void sourceRollsFollowTheFileContributionsInOwnerOrder() {
        LootableContributionTest.load(LootableAsset.of("base", new Roll[] {granting("Staple")}),
                LootableAsset.of("extra", new Roll[] {granting("Bonus")}, null, "base"));
        // Registered in the reverse of owner order: the order rolls append in is the owners', never
        // the order mods happened to set up in.
        LootableConfig.getInstance().registerRollSource(OWNER_Z, onlyFor("base", "Zed"));
        LootableConfig.getInstance().registerRollSource(OWNER_A, onlyFor("base", "Ay"));

        LootableAsset resolved = LootableConfig.getInstance().resolve("BASE");
        assertNotNull(resolved);
        assertEquals(List.of("Staple", "Bonus", "Ay", "Zed"), itemIdsOf(resolved.rollsOrEmpty()));
    }

    @Test
    void everyEngineReadSeesTheSourceRollsBeforeTheRefsInlineRolls() {
        LootableContributionTest.load(LootableAsset.of("base", new Roll[] {granting("Staple")}));
        LootableConfig.getInstance().registerRollSource(OWNER_A, onlyFor("base", "Sourced"));
        LootRef ref = LootRef.of(new String[] {"base"}, new Roll[] {granting("Inline")});

        assertEquals(List.of("Staple", "Sourced", "Inline"), itemIdsOf(LootEngine.resolve(ref, null).rolls()));
        assertEquals(List.of("Staple", "Sourced", "Inline"), itemIdsOf(LootEngine.resolveRolls(ref, null)));
    }

    @Test
    void aSourceKeepsTheTablesPoolAndLeavesTheAuthoredViewAlone() {
        LootPool pool = LootPool.of(null, new LootPool.Entry[] {LootableContributionTest.entry("Pick", 1)});
        LootableContributionTest.load(LootableAsset.of("base", new Roll[] {granting("Staple")}, pool, null));
        LootableConfig.getInstance().registerRollSource(OWNER_A, onlyFor("base", "Sourced"));

        assertNotNull(LootableConfig.getInstance().resolve("base").getPool(), "a source adds rolls, never a bag");
        assertEquals(List.of("Staple"),
                itemIdsOf(LootableConfig.getInstance().resolveAuthored("base").rollsOrEmpty()),
                "what a file wrote is still what its author is shown");
    }

    @Test
    void aSourceNeverConjuresATableNoFileShips() {
        LootableConfig.getInstance().registerRollSource(OWNER_A, id -> List.of(granting("Sourced")));

        assertNull(LootableConfig.getInstance().resolve("absent_table"));
    }

    @Test
    void anUnregisteredSourcesRollsVanish() {
        LootableContributionTest.load(LootableAsset.of("base", new Roll[] {granting("Staple")}));
        LootableConfig.getInstance().registerRollSource(OWNER_A, onlyFor("base", "Sourced"));
        LootableConfig.getInstance().unregisterRollSource(OWNER_A);

        assertEquals(List.of("Staple"), itemIdsOf(LootableConfig.getInstance().resolve("base").rollsOrEmpty()));
    }

    @Test
    void registeringAnOwnerAgainReplacesItsSource() {
        LootableContributionTest.load(LootableAsset.of("base", new Roll[] {granting("Staple")}));
        LootableConfig.getInstance().registerRollSource(OWNER_A, onlyFor("base", "Old"));
        LootableConfig.getInstance().registerRollSource(OWNER_A.toUpperCase(Locale.ROOT),
                onlyFor("base", "New"));

        assertEquals(List.of("Staple", "New"),
                itemIdsOf(LootableConfig.getInstance().resolve("base").rollsOrEmpty()),
                "the owner id matches without regard to case, and the second source replaces the first");
    }

    @Test
    void aSourceThatThrowsCostsOnlyItsOwnRollsAndWarnsOnce() {
        LootableContributionTest.load(LootableAsset.of("base", new Roll[] {granting("Staple")}));
        LootableConfig.getInstance().warnInto(warnings::add);
        LootableConfig.getInstance().registerRollSource(OWNER_A, id -> {
            throw new IllegalStateException("fixture failure");
        });
        LootableConfig.getInstance().registerRollSource(OWNER_Z, onlyFor("base", "Healthy"));

        assertEquals(List.of("Staple", "Healthy"),
                itemIdsOf(LootableConfig.getInstance().resolve("base").rollsOrEmpty()));
        assertEquals(List.of("Staple", "Healthy"),
                itemIdsOf(LootableConfig.getInstance().resolve("base").rollsOrEmpty()));
        assertEquals(1, warnings.size(), "a broken source is reported once, not on every resolve: " + warnings);
    }
}
