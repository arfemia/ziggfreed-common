package com.ziggfreed.common.stats.condition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entity.condition.Condition;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The bound grants' hit term (R945), over hand-built entries and a stand-in hit read: a modifier that is on already
 * sits on the stat, so a hit never pays it again; only an off one whose list names a hit-aware condition, an additive
 * amount on the stat's maximum, on the stat the reader adds up, and whose conditions hold on the hit is paid. No amount
 * is pinned beyond the ones the entries carry.
 */
class BoundGrantsHitTermTest {

    private static final int STAT = 5;
    private static final int OTHER_STAT = 6;

    /** A hit-aware condition; the hit read is the test's stand-in, so neither answer here is ever asked. */
    private static final class OnHit extends Condition implements HitAwareCondition {
        OnHit() {
            super(false);
        }

        @Override
        public boolean eval0(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref,
                @Nonnull Instant now) {
            return false;
        }

        @Override
        public boolean evalOnHit(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> attacker,
                @Nonnull Damage damage, @Nonnull Instant now) {
            return true;
        }
    }

    /** A condition that reads only the holder. */
    private static final class Plain extends Condition {
        Plain() {
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
    void anOnModifierIsNeverPaidOnAHit_onlyAnOffOneWhoseConditionsHoldOnIt() {
        Condition[] weapon = {new OnHit(), new Plain()};
        BoundGrants.Entry on = entry("on", STAT, 3.0f, StaticModifier.CalculationType.ADDITIVE, weapon, true);
        BoundGrants.Entry off = entry("off", STAT, 2.0f, StaticModifier.CalculationType.ADDITIVE, weapon, false);
        List<BoundGrants.Entry> entries = List.of(on, off);

        double paid = BoundGrants.hitSum(entries, (stat, id) -> stat == STAT, conditions -> true);
        assertEquals(2.0, paid, 1e-9, "the on modifier is already on the stat; only the off one is added on the hit");

        assertEquals(0.0, BoundGrants.hitSum(entries, (stat, id) -> stat == STAT, conditions -> false), 1e-9,
                "an off modifier whose conditions do not hold on the hit is not paid");

        off.on = true;
        assertEquals(0.0, BoundGrants.hitSum(entries, (stat, id) -> stat == STAT, conditions -> true), 1e-9,
                "once the pass puts it on, the hit never pays it a second time");
    }

    @Test
    void onlyAHitAwareAdditiveModifierOnTheReadStatIsPaid() {
        Condition[] weapon = {new OnHit()};
        Condition[] holderOnly = {new Plain()};
        BoundGrants.Entry plain = entry("plain", STAT, 4.0f, StaticModifier.CalculationType.ADDITIVE, holderOnly,
                false);
        BoundGrants.Entry multiplying = entry("times", STAT, 0.5f, StaticModifier.CalculationType.MULTIPLICATIVE,
                weapon, false);
        BoundGrants.Entry elsewhere = entry("other", OTHER_STAT, 7.0f, StaticModifier.CalculationType.ADDITIVE,
                weapon, false);
        BoundGrants.Entry paid = entry("paid", STAT, 1.0f, StaticModifier.CalculationType.ADDITIVE, weapon, false);

        assertFalse(plain.hitAware, "a list with no hit-aware condition cannot flip on a hit");
        assertTrue(paid.hitAware);
        int[] asked = new int[1];
        double sum = BoundGrants.hitSum(List.of(plain, multiplying, elsewhere, paid), (stat, id) -> stat == STAT,
                conditions -> {
                    asked[0]++;
                    return true;
                });
        assertEquals(1.0, sum, 1e-9, "a holder-only list, a multiplying modifier and another stat are never paid");
        assertEquals(1, asked[0], "the conditions are asked only of the one modifier the hit may pay");
    }
}
