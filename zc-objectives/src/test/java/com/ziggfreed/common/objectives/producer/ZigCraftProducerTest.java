package com.ziggfreed.common.objectives.producer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pins {@link ZigCraftProducer#craftBatchAmount}, the clamp that makes a batch craft (crafting 10x
 * in one action) count every crafted unit toward objective progress instead of treating the
 * engine's one {@code CraftRecipeEvent.Post} as a single unit, and a zero or negative engine
 * quantity as one thing crafted rather than nothing.
 *
 * <p>Mechanics only: no store, no engine, just the clamp against fixture inputs. A consumer that
 * awards XP per craft ACTION deliberately does not read this amount, and that is its business.
 */
class ZigCraftProducerTest {

    @Test
    void positiveQuantityPassesThrough() {
        assertEquals(1L, ZigCraftProducer.craftBatchAmount(1));
        assertEquals(10L, ZigCraftProducer.craftBatchAmount(10));
    }

    @Test
    void zeroOrNegativeQuantityClampsToOne() {
        assertEquals(1L, ZigCraftProducer.craftBatchAmount(0));
        assertEquals(1L, ZigCraftProducer.craftBatchAmount(-1));
        assertEquals(1L, ZigCraftProducer.craftBatchAmount(-100));
    }
}
