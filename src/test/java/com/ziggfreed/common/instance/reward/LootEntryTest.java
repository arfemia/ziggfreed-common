package com.ziggfreed.common.instance.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * The compact pool-spec grammar ({@code [w<n>] [s<n>] <kind> <id> <qty|min-max> [displayKey]}) and the
 * quantity-range resolve. Each assertion guards a parse path the loot tables depend on.
 */
class LootEntryTest {

    @Test
    void parsesBareItemWithDefaults() {
        LootEntry e = LootEntry.parse("item Foo 2");
        assertNotNull(e);
        assertEquals(InstanceReward.Kind.ITEM, e.kind());
        assertEquals("Foo", e.id());
        assertEquals(2, e.qtyMin());
        assertEquals(2, e.qtyMax());
        assertEquals(1, e.weight());
        assertEquals(0, e.minScore());
        assertNull(e.displayKey());
    }

    @Test
    void parsesWeightScoreRangeAndDisplayKey() {
        LootEntry e = LootEntry.parse("w10 s2000 item Bar 1-3 my.key");
        assertNotNull(e);
        assertEquals(10, e.weight());
        assertEquals(2000, e.minScore());
        assertEquals(1, e.qtyMin());
        assertEquals(3, e.qtyMax());
        assertEquals("my.key", e.displayKey());
    }

    @Test
    void flagOrderIsInsensitive() {
        LootEntry e = LootEntry.parse("s500 w7 item Baz 2-4");
        assertNotNull(e);
        assertEquals(7, e.weight());
        assertEquals(500, e.minScore());
    }

    @Test
    void parsesCurrencyKind() {
        LootEntry e = LootEntry.parse("currency bounty_token 50");
        assertNotNull(e);
        assertEquals(InstanceReward.Kind.CURRENCY, e.kind());
        assertEquals("bounty_token", e.id());
        assertEquals(50, e.qtyMin());
    }

    @Test
    void rejectsMalformedSpecs() {
        assertNull(LootEntry.parse(null));
        assertNull(LootEntry.parse(""));
        assertNull(LootEntry.parse("   "));
        assertNull(LootEntry.parse("item Foo"));        // too few tokens
        assertNull(LootEntry.parse("banana Foo 2"));    // unknown kind
        assertNull(LootEntry.parse("item Foo x"));      // non-numeric qty
        assertNull(LootEntry.parse("item Foo 0"));      // qty < 1
        assertNull(LootEntry.parse("item Foo 3-1"));    // max < min
        assertNull(LootEntry.parse("w10 s50"));         // flags only, no reward tail
    }

    @Test
    void resolveRollsQuantityWithinRange() {
        LootEntry e = LootEntry.parse("item Foo 2-5");
        assertNotNull(e);
        Random rng = new Random(123);
        for (int i = 0; i < 200; i++) {
            InstanceReward r = e.resolve(rng);
            assertEquals("Foo", r.id());
            assertTrue(r.quantity() >= 2 && r.quantity() <= 5, "qty in [2,5] but was " + r.quantity());
        }
    }

    @Test
    void resolveFixedQuantityForDegenerateRange() {
        LootEntry e = LootEntry.parse("item Foo 3");
        assertNotNull(e);
        assertEquals(3, e.resolve(new Random(1)).quantity());
    }

    @Test
    void safeWeightClampsNegative() {
        LootEntry e = new LootEntry(InstanceReward.Kind.ITEM, "Foo", 1, 1, -5, 0, null);
        assertEquals(0.0, e.safeWeight());
    }

    @Test
    void parseAllSkipsMalformed() {
        assertEquals(2, LootEntry.parseAll(new String[]{"item A 1", "garbage", "w2 item B 1-2"}).size());
        assertTrue(LootEntry.parseAll(null).isEmpty());
    }
}
