package com.ziggfreed.common.grant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The grant rebuild is idempotent: a holder's modifiers planned against what is on the entity become exactly the
 * wanted ones (a stale key taken off, a modifier of another shape replaced, a wanted amount of 0 not written), and
 * planning again against what the first rebuild left plans nothing, so a second rebuild leaves the same modifiers.
 */
class StatGrantPlanTest {

    private static final StatGrantPlan.Target HEALTH = new StatGrantPlan.Target(1, "zigtrack:your_mining:lines");
    private static final StatGrantPlan.Target STALE = new StatGrantPlan.Target(2, "zigtrack:your_mining:gone");
    private static final StatGrantPlan.Target ODD = new StatGrantPlan.Target(3, "zigtrack:your_mining:odd");
    private static final StatGrantPlan.Target ZERO = new StatGrantPlan.Target(4, "zigtrack:your_mining:zero");

    @Test
    void rebuildingTwiceLeavesTheSameModifiers() {
        Map<StatGrantPlan.Target, Float> existing = new LinkedHashMap<>();
        existing.put(STALE, 4f);
        existing.put(ODD, Float.NaN);
        Map<StatGrantPlan.Target, Float> wanted = new LinkedHashMap<>();
        wanted.put(HEALTH, 6f);
        wanted.put(ODD, 2f);
        wanted.put(ZERO, 0f);

        StatGrantPlan.Plan first = StatGrantPlan.plan(existing, wanted);
        Map<StatGrantPlan.Target, Float> once = StatGrantPlan.applied(existing, first);
        assertEquals(Map.of(HEALTH, 6f, ODD, 2f), once,
                "the stale key goes, the odd modifier is replaced, a wanted 0 is not written");

        StatGrantPlan.Plan second = StatGrantPlan.plan(once, wanted);
        assertTrue(second.isEmpty(), "a second rebuild plans nothing");
        assertEquals(once, StatGrantPlan.applied(once, second), "and leaves the same modifiers");

        StatGrantPlan.Plan cleared = StatGrantPlan.plan(once, Map.of());
        assertTrue(StatGrantPlan.applied(once, cleared).isEmpty(), "wanting nothing takes every one of them off");
    }
}
