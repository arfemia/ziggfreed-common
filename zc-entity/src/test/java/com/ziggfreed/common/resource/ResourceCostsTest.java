package com.ziggfreed.common.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.protocol.ValueType;

/**
 * The cost math every consumer pays through: a Percent cost is a share of the stat's span, an empty pool is NO_POOL
 * (never SHORT), and a fractional amount is asked and judged as it is.
 */
class ResourceCostsTest {

    @Test
    void percentIsAShareOfTheSpan_absoluteIsPoints() {
        // A mace's Signature bar: Min 0, Max 8. Half of it is 4 points, all of it 8.
        assertEquals(4.0, ResourceCosts.points(50, ValueType.Percent, 0, 8), 1e-9);
        assertEquals(8.0, ResourceCosts.points(100, ValueType.Percent, 0, 8), 1e-9);
        // Stamina spans -4 to 10, so half of it is 7, as the engine's own Percent reads it.
        assertEquals(7.0, ResourceCosts.points(50, ValueType.Percent, -4, 10), 1e-9);
        assertEquals(30.0, ResourceCosts.points(30, ValueType.Absolute, 0, 100), 1e-9);
        assertEquals(0.0, ResourceCosts.points(0, ValueType.Absolute, 0, 100), 1e-9);
        assertEquals(0.0, ResourceCosts.points(Double.NaN, ValueType.Absolute, 0, 100), 1e-9);
    }

    @Test
    void anEmptyPoolIsNoPool_neverShort() {
        // No bar weapon in hand: the Signature bar has a Max of 0.
        assertEquals(ResourceCosts.Verdict.NO_POOL, ResourceCosts.judge(100, ValueType.Percent, 0, 0, 0).verdict());
        assertEquals(ResourceCosts.Verdict.NO_POOL, ResourceCosts.judge(25, ValueType.Percent, 0, 0, 0).verdict());
        assertEquals(ResourceCosts.Verdict.NO_POOL, ResourceCosts.judge(5, ValueType.Absolute, 0, 0, 0).verdict());
        // A free cost is paid by anyone, an empty pool included.
        assertTrue(ResourceCosts.judge(0, ValueType.Percent, 0, 0, 0).ok());
    }

    @Test
    void aShareIsJudgedAgainstWhatSitsAboveMin() {
        assertTrue(ResourceCosts.judge(100, ValueType.Percent, 8, 0, 8).ok(), "a full bar pays a full bar");
        ResourceCosts.Check short4 = ResourceCosts.judge(100, ValueType.Percent, 7, 0, 8);
        assertEquals(ResourceCosts.Verdict.SHORT, short4.verdict());
        assertEquals(7.0, short4.have(), 1e-9);
        assertEquals(8.0, short4.need(), 1e-9);
        assertTrue(ResourceCosts.judge(50, ValueType.Percent, 4, 0, 8).ok(), "half a bar pays half a bar");
        // Stamina at 3 of -4..10 holds 7 above Min: exactly half its span.
        assertTrue(ResourceCosts.judge(50, ValueType.Percent, 3, -4, 10).ok());
        assertFalse(ResourceCosts.judge(50, ValueType.Percent, 2.9, -4, 10).ok());
    }

    @Test
    void aFractionalCostIsAskedAndJudgedAsItIs() {
        // A 6% cut on a 3 stamina dodge leaves 2.82 (R971).
        ResourceCosts.Check paid = ResourceCosts.judge(2.82, ValueType.Absolute, 2.82, -4, 10);
        assertTrue(paid.ok());
        assertEquals(2.82, paid.need(), 1e-12);
        assertEquals(ResourceCosts.Verdict.SHORT, ResourceCosts.judge(2.82, ValueType.Absolute, 2.81, -4, 10).verdict());
    }

    @Test
    void theValueTypeReadsTheEngineWordsInAnyCase() {
        assertEquals(ValueType.Percent, ResourceCost.of("SignatureEnergy", 50, ValueType.Percent).valueType());
        assertEquals(ValueType.Absolute, ResourceCost.of("Mana", 30, null).valueType());
        assertTrue(ResourceCost.of("Mana", 30, null).valueTypeKnown());
        assertTrue(ResourceCost.of(" ", 30, null).isFree(), "no stat costs nothing");
        assertTrue(ResourceCost.of("Mana", 0, null).isFree());
        assertFalse(ResourceCost.of("Mana", 0.5, null).isFree());
    }
}
