package com.ziggfreed.common.loot.trigger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.factor.FactorFormula;
import com.ziggfreed.common.loot.LootEngine;
import com.ziggfreed.common.loot.LootGrants;
import com.ziggfreed.common.loot.LootRef;
import com.ziggfreed.common.loot.LootableAsset;
import com.ziggfreed.common.loot.LootableConfig;
import com.ziggfreed.common.loot.Roll;

/** What a row rolls right now, and how a table it names that is not loaded is reported. */
class BonusRollsTest {

    private final List<String> warnings = new ArrayList<>();

    @AfterEach
    void reset() {
        LootableConfig.getInstance().mergePackLayer(Map.of());
    }

    private record Fixture(@Nonnull String sourceId, @Nonnull LootRef loot) implements BonusEntry {
        @Override
        @Nonnull
        public BonusMoment moment() {
            return BonusMoment.BREAK_BLOCK;
        }

        @Override
        @Nullable
        public FactorFormula chance() {
            return null;
        }
    }

    private static Roll granting(String itemId) {
        return Roll.of(null, null, null, null, LootGrants.ofItem(itemId, 1), null);
    }

    private static String itemOf(Roll roll) {
        return roll.getGrants().getItems()[0].getItem();
    }

    private static Fixture rowNamingAMissingTable() {
        LootableConfig.getInstance().mergePackLayer(Map.of("fixture_finds",
                LootableAsset.of("fixture_finds", new Roll[] {granting("Fixture_Gem")})));
        return new Fixture("fixture_row", LootRef.of(new String[] {"fixture_missing", "fixture_finds"},
                new Roll[] {granting("Fixture_Inline")}));
    }

    @Test
    void aMissingTableIsWarnedOnceAndTheRestStillRolls() {
        BonusRolls rolls = new BonusRolls(warnings::add);
        Fixture row = rowNamingAMissingTable();

        List<Roll> first = rolls.rolls(row);
        List<Roll> second = rolls.rolls(row);

        assertEquals(List.of("Fixture_Gem", "Fixture_Inline"), first.stream().map(BonusRollsTest::itemOf).toList(),
                "the loaded table's rolls, then the row's own");
        assertEquals(2, second.size());
        assertEquals(1, warnings.size(), "one warning however many moments ask: " + warnings);
        assertTrue(warnings.get(0).contains("fixture_missing") && warnings.get(0).contains("fixture_row"),
                warnings.get(0));
    }

    @Test
    void aResetReArmsTheWarning() {
        BonusRolls rolls = new BonusRolls(warnings::add);
        Fixture row = rowNamingAMissingTable();

        rolls.rolls(row);
        rolls.reset();
        rolls.resolved(row);

        assertEquals(2, warnings.size(), "a refold may have fixed or broken any reference");
    }

    @Test
    void theWholeRefResolvesTablesBeforeInlineRolls() {
        BonusRolls rolls = new BonusRolls(warnings::add);

        LootEngine.Resolved resolved = rolls.resolved(rowNamingAMissingTable());

        assertEquals(List.of("Fixture_Gem", "Fixture_Inline"),
                resolved.rolls().stream().map(BonusRollsTest::itemOf).toList());
        assertTrue(resolved.pools().isEmpty(), "the fixture table has no pool");
    }
}
