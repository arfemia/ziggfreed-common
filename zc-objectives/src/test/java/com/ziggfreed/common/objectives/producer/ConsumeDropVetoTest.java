package com.ziggfreed.common.objectives.producer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/** A drop from the active slot is never a meal: the drop request's mark vetoes the one removal it causes. */
class ConsumeDropVetoTest {

    @Test
    void theRemovalADropCausesIsVetoedOnceAndAMealAfterItCounts() {
        UUID player = UUID.randomUUID();
        ZigConsumeItemProducer.markDrop(player, 2, 1_000L);

        assertFalse(ZigConsumeItemProducer.droppedJustNow(player, 3, 1_100L), "another slot is not the drop");
        assertTrue(ZigConsumeItemProducer.droppedJustNow(player, 2, 1_100L), "the dropped slot's removal is the drop");
        assertFalse(ZigConsumeItemProducer.droppedJustNow(player, 2, 1_200L), "the mark is spent, so a meal counts");
    }

    @Test
    void aMarkOlderThanTheWindowVetoesNothing() {
        UUID player = UUID.randomUUID();
        ZigConsumeItemProducer.markDrop(player, 0, 0L);

        assertFalse(ZigConsumeItemProducer.droppedJustNow(player, 0, ZigConsumeItemProducer.DROP_VETO_MS));
        assertFalse(ZigConsumeItemProducer.droppedJustNow(null, 0, 0L));
    }
}
