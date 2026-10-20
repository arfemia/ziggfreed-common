package com.ziggfreed.common.stats.condition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.stats.StatTypeCeiling;
import com.ziggfreed.common.validation.Finding;

/**
 * The rules conditional stats rest on that have no engine to stand behind them (R757, R759): the first-hit table
 * forgets a target the moment it leaves, so a creature met again is a first hit again; an effect family folds its
 * includes and every tagged effect in, a loop counting each member once; the stat-type count warns only past the
 * ceiling; and the holder conditions' hold time counts from when they began holding. No size or ceiling value is
 * pinned beyond the constant the boot audit itself names.
 */
class ConditionalStatsRulesTest {

    @AfterEach
    void clear() {
        FirstHits.clear();
    }

    @Test
    void theFirstHitTableForgetsATargetThatLeft_soItIsAFirstHitAgain() {
        UUID player = new UUID(0L, 1L);
        Ref<EntityStore> foe = new Ref<>(null, 2);
        UUID other = new UUID(0L, 3L);
        assertFalse(FirstHits.hitBefore(player, foe), "a foe never hit has not been hit before");
        assertFalse(FirstHits.hitBefore(null, foe), "an attacker with no id has never hit anything");
        FirstHits.record(player, foe);
        assertTrue(FirstHits.hitBefore(player, foe));
        assertFalse(FirstHits.hitBefore(other, foe), "another attacker's first hit is still its first");
        FirstHits.forget(foe);
        assertFalse(FirstHits.hitBefore(player, foe), "a target that left is forgotten");
        assertEquals(0, FirstHits.size(), "nothing is kept for a target that left");
    }

    @Test
    void aFamilyFoldsItsIncludesAndTaggedEffects_aLoopCountingEachOnce() {
        Map<String, EffectFamilyAsset> listed = new LinkedHashMap<>();
        listed.put("slowed", EffectFamilyAsset.of("Slowed", new String[] {"Slow"}, null));
        listed.put("rooted", EffectFamilyAsset.of("Rooted", new String[] {"Root"}, null));
        listed.put("controlled", EffectFamilyAsset.of("Controlled", null, new String[] {"Slowed", "Rooted",
                "Controlled"}));
        listed.put("a", EffectFamilyAsset.of("A", new String[] {"One"}, new String[] {"B"}));
        listed.put("b", EffectFamilyAsset.of("B", new String[] {"Two"}, new String[] {"A"}));
        Map<String, Set<String>> folded = EffectFamilies.fold(listed, Map.of("mod_frost", Set.of("slowed")));
        assertEquals(Set.of("slow", "mod_frost"), folded.get("slowed"), "a mod's tagged effect joins its family");
        assertEquals(Set.of("slow", "mod_frost", "root"), folded.get("controlled"),
                "an including family takes every member of the families it includes, itself named once");
        assertEquals(Set.of("one", "two"), folded.get("a"), "a loop counts each member once");
    }

    @Test
    void theStatTypeCountWarnsOnlyPastTheCeiling() {
        List<Finding> at = StatTypeCeiling.audit(StatTypeCeiling.CEILING);
        assertEquals(1, at.size(), "at the ceiling: the count alone");
        assertFalse(at.get(0).isProblem());
        List<Finding> past = StatTypeCeiling.audit(StatTypeCeiling.CEILING + 1);
        assertTrue(past.stream().anyMatch(f -> StatTypeCeiling.PAST_CEILING.equals(f.code()) && f.isProblem()),
                "past the ceiling: a warning");
    }

    @Test
    void theHoldTimeCountsFromWhenTheConditionsBeganHolding() {
        long second = 1_000_000_000L;
        assertFalse(ConditionalStats.heldLongEnough(-1L, 10 * second, 0.0), "conditions not holding never count");
        assertTrue(ConditionalStats.heldLongEnough(5 * second, 5 * second, 0.0), "no hold time: at once");
        assertFalse(ConditionalStats.heldLongEnough(5 * second, 6 * second, 2.0), "one second of two");
        assertTrue(ConditionalStats.heldLongEnough(5 * second, 7 * second, 2.0), "two seconds of two");
    }
}
