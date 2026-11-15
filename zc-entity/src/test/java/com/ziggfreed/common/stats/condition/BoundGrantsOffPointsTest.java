package com.ziggfreed.common.stats.condition;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entity.condition.Condition;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The bound grants' off and on readings, over hand-built entries and a stand-in read of the other conditions: a
 * modifier that is on already sits on the stat, so the off reading never counts it again; an off one counts only when
 * its list names a condition the reader's predicate accepts and every other condition in it holds; the on reading
 * counts only an on modifier whose list names a match, so no modifier is in both; each only as an additive amount on
 * the read stat's maximum. No amount is pinned beyond the ones the entries carry.
 */
class BoundGrantsOffPointsTest {

    private static final int STAT = 5;
    private static final int OTHER_STAT = 6;

    /** A condition the reader stands in for; the reads here are the test's, so it is never asked. */
    private static final class Named extends Condition {
        Named() {
            super(false);
        }

        @Override
        public boolean eval0(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref,
                @Nonnull Instant now) {
            return false;
        }
    }

    /** Any other condition. */
    private static final class Other extends Condition {
        Other() {
            super(false);
        }

        @Override
        public boolean eval0(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref,
                @Nonnull Instant now) {
            return false;
        }
    }

    @Nonnull
    private static BoundGrants.Entry entry(String id, int stat, float amount, StaticModifier.CalculationType type,
            Condition[] conditions, boolean on) {
        BoundGrants.Entry entry = new BoundGrants.Entry(BoundGrants.MODIFIER_PREFIX + "test:" + id, stat, "Stat_" + stat,
                new StaticModifier(Modifier.ModifierTarget.MAX, type, amount), conditions);
        entry.on = on;
        return entry;
    }

    @Test
    void anOnModifierIsNeverCounted_anOffOneOnlyWhenItsListNamesAMatch() {
        Condition[] skill = {new Named()};
        Condition[] elsewhere = {new Other()};
        BoundGrants.Entry on = entry("on", STAT, 3.0f, StaticModifier.CalculationType.ADDITIVE, skill, true);
        BoundGrants.Entry off = entry("off", STAT, 2.0f, StaticModifier.CalculationType.ADDITIVE, skill, false);
        BoundGrants.Entry unnamed = entry("unnamed", STAT, 7.0f, StaticModifier.CalculationType.ADDITIVE, elsewhere,
                false);
        List<BoundGrants.Entry> entries = List.of(on, off, unnamed);

        assertEquals(2.0, BoundGrants.offSum(entries, STAT, c -> c instanceof Named, c -> true), 1e-9,
                "the on modifier is already on the stat, and a list naming no match never counts");

        off.on = true;
        assertEquals(0.0, BoundGrants.offSum(entries, STAT, c -> c instanceof Named, c -> true), 1e-9,
                "once the pass puts it on, the off reading never counts it a second time");
    }

    @Test
    void everyOtherConditionMustHold_andOnlyAnAdditiveAmountOnTheReadStatCounts() {
        Condition[] withOther = {new Named(), new Other()};
        BoundGrants.Entry paired = entry("paired", STAT, 4.0f, StaticModifier.CalculationType.ADDITIVE, withOther,
                false);
        BoundGrants.Entry multiplying = entry("times", STAT, 0.5f, StaticModifier.CalculationType.MULTIPLICATIVE,
                new Condition[] {new Named()}, false);
        BoundGrants.Entry otherStat = entry("other", OTHER_STAT, 9.0f, StaticModifier.CalculationType.ADDITIVE,
                new Condition[] {new Named()}, false);
        List<BoundGrants.Entry> entries = List.of(paired, multiplying, otherStat);

        int[] asked = new int[1];
        double none = BoundGrants.offSum(entries, STAT, c -> c instanceof Named, c -> {
            asked[0]++;
            return false;
        });
        assertEquals(0.0, none, 1e-9, "a list whose other condition does not hold is not counted");
        assertEquals(1, asked[0], "only the other condition of the one modifier the reading may count is asked");

        assertEquals(4.0, BoundGrants.offSum(entries, STAT, c -> c instanceof Named, c -> true), 1e-9,
                "a multiplying modifier and another stat's never count");
    }

    @Test
    void theOnReadingCountsOnlyOnModifiersNamingAMatch_soNoModifierIsCountedByBoth() {
        Condition[] named = {new Named()};
        Condition[] elsewhere = {new Other()};
        BoundGrants.Entry on = entry("on", STAT, 3.0f, StaticModifier.CalculationType.ADDITIVE, named, true);
        BoundGrants.Entry off = entry("off", STAT, 2.0f, StaticModifier.CalculationType.ADDITIVE, named, false);
        BoundGrants.Entry unnamed = entry("unnamed", STAT, 7.0f, StaticModifier.CalculationType.ADDITIVE, elsewhere,
                true);
        BoundGrants.Entry multiplying = entry("times", STAT, 0.5f, StaticModifier.CalculationType.MULTIPLICATIVE,
                named, true);
        BoundGrants.Entry otherStat = entry("other", OTHER_STAT, 9.0f, StaticModifier.CalculationType.ADDITIVE, named,
                true);
        List<BoundGrants.Entry> entries = List.of(on, off, unnamed, multiplying, otherStat);

        assertEquals(3.0, BoundGrants.onSum(entries, STAT, c -> c instanceof Named), 1e-9,
                "only the on additive modifier on the read stat whose list names a match");
        assertEquals(2.0, BoundGrants.offSum(entries, STAT, c -> c instanceof Named, c -> true), 1e-9,
                "the off reading takes the other one");

        on.on = false;
        off.on = true;
        assertEquals(2.0, BoundGrants.onSum(entries, STAT, c -> c instanceof Named), 1e-9,
                "a flip moves a modifier from one reading to the other");
        assertEquals(3.0, BoundGrants.offSum(entries, STAT, c -> c instanceof Named, c -> true), 1e-9,
                "and never into both");
    }
}
