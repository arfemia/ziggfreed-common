package com.ziggfreed.common.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The stored-versus-effective status rule and the cooldown boundaries, driven through the pure
 * overloads so the clock is an argument rather than the wall.
 */
class QuestLifecycleTest {

    private static final long HOUR = 3_600_000L;
    private static final Quest.Repeat DAILY = Quest.Repeat.every(24 * HOUR);

    @Test
    void aOneShotQuestIsNeverReinterpreted() {
        assertEquals(QuestStatus.COMPLETED,
                QuestLifecycle.effectiveStatus(Quest.Repeat.ONCE, QuestStatus.COMPLETED, 1L, 2L));
    }

    @Test
    void everyNonCompletedStatusPassesThroughUntouched() {
        for (QuestStatus stored : new QuestStatus[] {
                QuestStatus.NOT_STARTED, QuestStatus.ACTIVE, QuestStatus.COMPLETED_UNCLAIMED}) {
            assertEquals(stored, QuestLifecycle.effectiveStatus(DAILY, stored, 1L, 2L));
        }
    }

    @Test
    void aFinishedRepeatableReadsOnCooldownUntilTheWindowElapses() {
        long stamped = 1_000_000L;
        assertEquals(QuestStatus.ON_COOLDOWN,
                QuestLifecycle.effectiveStatus(DAILY, QuestStatus.COMPLETED, stamped, stamped + HOUR));
    }

    @Test
    void theBoundaryIsInclusiveAtTheEnd() {
        long stamped = 1_000_000L;
        long expiry = stamped + DAILY.cooldownMs();
        assertEquals(QuestStatus.ON_COOLDOWN,
                QuestLifecycle.effectiveStatus(DAILY, QuestStatus.COMPLETED, stamped, expiry - 1),
                "one millisecond short is still on cooldown");
        assertEquals(QuestStatus.NOT_STARTED,
                QuestLifecycle.effectiveStatus(DAILY, QuestStatus.COMPLETED, stamped, expiry),
                "the instant it elapses the quest is offerable again");
        assertEquals(QuestStatus.NOT_STARTED,
                QuestLifecycle.effectiveStatus(DAILY, QuestStatus.COMPLETED, stamped, expiry + HOUR));
    }

    @Test
    void anUnstampedRepeatableIsNotOnCooldown() {
        assertEquals(QuestStatus.NOT_STARTED,
                QuestLifecycle.effectiveStatus(DAILY, QuestStatus.COMPLETED, 0L, 5_000L));
        assertEquals(0L, QuestLifecycle.cooldownRemainingMs(DAILY, 0L, 5_000L));
    }

    @Test
    void remainingCountsDownAndNeverGoesNegative() {
        long stamped = 1_000_000L;
        assertEquals(DAILY.cooldownMs(), QuestLifecycle.cooldownRemainingMs(DAILY, stamped, stamped));
        assertEquals(DAILY.cooldownMs() - HOUR,
                QuestLifecycle.cooldownRemainingMs(DAILY, stamped, stamped + HOUR));
        assertEquals(0L, QuestLifecycle.cooldownRemainingMs(DAILY, stamped, stamped + 10 * 24 * HOUR));
    }

    @Test
    void aBackwardsClockReportsTheFullWindowRatherThanNonsense() {
        long stamped = 1_000_000L;
        assertEquals(DAILY.cooldownMs() + HOUR,
                QuestLifecycle.cooldownRemainingMs(DAILY, stamped, stamped - HOUR));
        assertTrue(QuestLifecycle.onCooldown(DAILY, stamped, stamped - HOUR));
    }

    @Test
    void aOneShotQuestNeverHasARemainingCooldown() {
        assertEquals(0L, QuestLifecycle.cooldownRemainingMs(Quest.Repeat.ONCE, 1L, 2L));
        assertFalse(QuestLifecycle.onCooldown(Quest.Repeat.ONCE, 1L, 2L));
    }

    @Test
    void cooldownFormattingIsCompactAndNeverNegative() {
        assertEquals("0m", QuestLifecycle.formatCooldown(0L));
        assertEquals("0m", QuestLifecycle.formatCooldown(-5L));
        assertEquals("5m", QuestLifecycle.formatCooldown(5 * 60_000L));
        assertEquals("2h 5m", QuestLifecycle.formatCooldown(2 * HOUR + 5 * 60_000L));
    }

    @Test
    void statusHelpersClassifyTheThreeInterestingCases() {
        assertTrue(QuestLifecycle.isInProgress(QuestStatus.ACTIVE));
        assertFalse(QuestLifecycle.isInProgress(QuestStatus.COMPLETED));
        assertTrue(QuestLifecycle.isFinished(QuestStatus.COMPLETED));
        assertTrue(QuestLifecycle.isFinished(QuestStatus.COMPLETED_UNCLAIMED));
        assertFalse(QuestLifecycle.isFinished(QuestStatus.ACTIVE));
        assertFalse(QuestLifecycle.isFinished(null));
    }

    @Test
    void statusParsesForgivinglyAndDefaultsToNotStarted() {
        assertEquals(QuestStatus.NOT_STARTED, QuestStatus.fromString(null));
        assertEquals(QuestStatus.NOT_STARTED, QuestStatus.fromString("nonsense"));
        assertEquals(QuestStatus.ACTIVE, QuestStatus.fromString(" active "));
        assertEquals(QuestStatus.COMPLETED_UNCLAIMED, QuestStatus.fromString("completed_unclaimed"));
    }

    @Test
    void repeatFactoriesSetTheKnobsTheyName() {
        assertFalse(Quest.Repeat.ONCE.repeatable());
        assertTrue(Quest.Repeat.every(HOUR).repeatable());
        assertFalse(Quest.Repeat.every(HOUR).stampOnPark());
        assertTrue(Quest.Repeat.everyStampedOnPark(HOUR).stampOnPark());
        assertEquals(0L, Quest.Repeat.every(-5L).cooldownMs(), "a negative window is clamped away");
    }
}
