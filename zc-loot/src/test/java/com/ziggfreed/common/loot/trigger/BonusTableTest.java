package com.ziggfreed.common.loot.trigger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.factor.FactorFormula;
import com.ziggfreed.common.loot.LootRef;

/**
 * Which row covers a name in a moment: the fold every bonus table shares and the most-specific
 * match. Every row here is the test's own; nothing shipped is read.
 */
class BonusTableTest {

    /** A row as small as the table needs: a moment, an id, and nothing to hand over. */
    private record Fixture(@Nonnull BonusMoment moment, @Nonnull String sourceId) implements BonusEntry {
        @Override
        @Nonnull
        public LootRef loot() {
            return LootRef.of(null, null);
        }

        @Override
        @Nullable
        public FactorFormula chance() {
            return null;
        }
    }

    private static BonusTable.Row<Fixture> row(BonusMoment moment, String match, String id) {
        return BonusTable.Row.of(match, new Fixture(moment, id));
    }

    private static String idFor(BonusTable<Fixture> table, BonusMoment moment, String name) {
        Fixture best = table.bestFor(moment, name);
        return best == null ? null : best.sourceId();
    }

    @Test
    void anExactPatternBeatsAPartialOneAndTheCatchAllComesLast() {
        BonusTable<Fixture> table = BonusTable.fold(List.of(
                row(BonusMoment.BREAK_BLOCK, "*", "all"),
                row(BonusMoment.BREAK_BLOCK, "Ore_*", "prefix"),
                row(BonusMoment.BREAK_BLOCK, "Ore_Iron", "exact")));

        assertEquals("exact", idFor(table, BonusMoment.BREAK_BLOCK, "Ore_Iron"));
        assertEquals("exact", idFor(table, BonusMoment.BREAK_BLOCK, "ORE_IRON"), "case is ignored");
        assertEquals("prefix", idFor(table, BonusMoment.BREAK_BLOCK, "Ore_Copper"));
        assertEquals("all", idFor(table, BonusMoment.BREAK_BLOCK, "Rock_Stone"));
    }

    @Test
    void aLongerCoreBeatsAShorterOne() {
        BonusTable<Fixture> table = BonusTable.fold(List.of(
                row(BonusMoment.KILL_MOB, "*Skeleton*", "family"),
                row(BonusMoment.KILL_MOB, "*Skeleton_Burnt*", "burnt")));

        assertEquals("burnt", idFor(table, BonusMoment.KILL_MOB, "Skeleton_Burnt_Archer"));
        assertEquals("family", idFor(table, BonusMoment.KILL_MOB, "Skeleton_Frost_Archer"));
    }

    @Test
    void twoRowsClaimingOnePatternInOneMomentAreOneAnswerAndTheLaterWinsInTheFirstsPlace() {
        BonusTable<Fixture> table = BonusTable.fold(List.of(
                row(BonusMoment.BREAK_BLOCK, "*Geode*", "first"),
                row(BonusMoment.BREAK_BLOCK, "Rock_*", "rock"),
                row(BonusMoment.BREAK_BLOCK, "*GEODE*", "second")));

        assertEquals("second", idFor(table, BonusMoment.BREAK_BLOCK, "Ore_Geode"));
        assertEquals(2, table.size());
        assertEquals(List.of("second", "rock"),
                table.entries().stream().map(Fixture::sourceId).toList(),
                "the later row takes the earlier one's place in fold order");
    }

    @Test
    void eachMomentKeepsItsOwnRows() {
        BonusTable<Fixture> table = BonusTable.fold(List.of(
                row(BonusMoment.BREAK_BLOCK, "*", "break"),
                row(BonusMoment.KILL_MOB, "*", "kill")));

        assertEquals("kill", idFor(table, BonusMoment.KILL_MOB, "Trork_Warrior"));
        assertEquals("break", idFor(table, BonusMoment.BREAK_BLOCK, "Trork_Warrior"));
        assertNull(idFor(table, BonusMoment.PICKUP_ITEM, "Trork_Warrior"));
    }

    @Test
    void aBlankOrMissingMatchCoversTheWholeMoment() {
        BonusTable<Fixture> blank = BonusTable.fold(List.of(row(BonusMoment.KILL_MOB, "  ", "blank")));
        BonusTable<Fixture> missing = BonusTable.fold(List.of(row(BonusMoment.KILL_MOB, null, "missing")));

        assertEquals("blank", idFor(blank, BonusMoment.KILL_MOB, "Skeleton_Burnt_Archer"));
        assertEquals("missing", idFor(missing, BonusMoment.KILL_MOB, "Skeleton_Burnt_Archer"));
    }

    @Test
    void aBlankOrMissingNameIsAnsweredByNothing() {
        BonusTable<Fixture> table = BonusTable.fold(List.of(row(BonusMoment.BREAK_BLOCK, "*", "all")));

        assertNull(table.bestFor(BonusMoment.BREAK_BLOCK, null));
        assertNull(table.bestFor(BonusMoment.BREAK_BLOCK, ""));
    }

    @Test
    void entriesWalkTheMomentsInDeclarationOrderThenFoldOrder() {
        BonusTable<Fixture> table = BonusTable.fold(List.of(
                row(BonusMoment.PICKUP_ITEM, "*", "pickup"),
                row(BonusMoment.BREAK_BLOCK, "Rock_*", "rock"),
                row(BonusMoment.KILL_MOB, "*", "kill"),
                row(BonusMoment.BREAK_BLOCK, "Ore_*", "ore")));

        assertEquals(List.of("rock", "ore", "kill", "pickup"),
                table.entries().stream().map(Fixture::sourceId).toList());
        assertEquals(4, table.size());
        assertEquals(List.of("Rock_*", "Ore_*"),
                table.patterned(BonusMoment.BREAK_BLOCK).stream().map(e -> e.getKey().raw).toList());
        assertTrue(BonusTable.<Fixture>fold(List.of()).entries().isEmpty());
    }
}
