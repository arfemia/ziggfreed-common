package com.ziggfreed.common.objectives.indicator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A consumer still drawing its own quest marks is noticed once per boot, and everyone who asked is told once. */
class QuestMarkYieldTest {

    @BeforeEach
    @AfterEach
    void reset() {
        QuestMarkYield.resetForTests();
    }

    @Test
    void nothingDrawsItsOwnUntilALegacyReadIsCalledAndTheFirstCallerIsKept() {
        assertFalse(QuestMarkYield.consumerDraws());
        assertNull(QuestMarkYield.via());

        QuestMarkYield.noteConsumerDraws("QuestIndicators.overheadAt");
        QuestMarkYield.noteConsumerDraws("QuestIndicators.mapMarksFor");

        assertTrue(QuestMarkYield.consumerDraws());
        assertEquals("QuestIndicators.overheadAt", QuestMarkYield.via());
    }

    @Test
    void everyListenerIsToldOnceAndAThrowingOneCostsOnlyItself() {
        AtomicInteger told = new AtomicInteger();
        QuestMarkYield.onConsumerDraws(() -> {
            throw new IllegalStateException("boom");
        });
        QuestMarkYield.onConsumerDraws(told::incrementAndGet);

        QuestMarkYield.noteConsumerDraws("a");
        QuestMarkYield.noteConsumerDraws("b");

        assertEquals(1, told.get());
    }
}
