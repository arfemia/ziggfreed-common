package com.ziggfreed.common.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.quest.Quest.Repeat;
import com.ziggfreed.common.quest.Quest.Repeat.CooldownFrom;
import com.ziggfreed.common.quest.Quest.Repeat.Reset;
import com.ziggfreed.common.quest.QuestProgressStore.CompletionRecord;

/**
 * The one evaluator: three independent constraints, ANDed, with the refusal a player is told chosen
 * so it is the most actionable one available.
 *
 * <p>Every case is driven through the pure overload, so the clock and the player's record are both
 * arguments. Fixtures are authored here on purpose - none of these numbers is balance data, they are
 * the boundaries themselves.
 */
class RepeatEvaluatorTest {

    private static final long HOUR = 3_600_000L;
    private static final long DAY = 24 * HOUR;

    /** A Monday-ish instant well clear of a boundary, so a case can move either way from it. */
    private static final long MIDDAY = 10 * DAY + 12 * HOUR;

    private static Repeat cooldownOnly(long ms) {
        return new Repeat(ms, CooldownFrom.CLAIM, null, 0);
    }

    private static Repeat windowOnly(int times) {
        return new Repeat(0L, CooldownFrom.CLAIM,
                new Reset(Reset.Period.DAILY, 0, DayOfWeek.MONDAY, times), 0);
    }

    private static Repeat cappedAt(int max) {
        return new Repeat(0L, CooldownFrom.CLAIM, null, max);
    }

    @Test
    void anEmptyGroupHoldsNothingBack() {
        QuestLifecycle.RepeatCheck check = QuestLifecycle.repeatCheck(Repeat.EXTERNALLY_GOVERNED, 0L,
                CompletionRecord.NONE, MIDDAY);

        assertTrue(check.available());
        assertNull(check.reason());
        assertEquals(0L, check.offerableAtMs());
    }

    @Test
    void aRollingCooldownRefusesUntilTheInstantItElapses() {
        Repeat repeat = cooldownOnly(4 * HOUR);
        long stamp = MIDDAY;

        QuestLifecycle.RepeatCheck refused =
                QuestLifecycle.repeatCheck(repeat, stamp, CompletionRecord.NONE, stamp + 4 * HOUR - 1);
        assertFalse(refused.available());
        assertEquals(QuestGates.REASON_ON_COOLDOWN, refused.reason());
        assertEquals(stamp + 4 * HOUR, refused.offerableAtMs());

        assertTrue(QuestLifecycle.repeatCheck(repeat, stamp, CompletionRecord.NONE, stamp + 4 * HOUR)
                .available(), "the instant it elapses the quest is offerable again");
    }

    @Test
    void aSpentWindowRefusesUntilTheNextOneAndNothingHasToSweepIt() {
        Repeat repeat = windowOnly(1);
        CompletionRecord spent = CompletionRecord.withoutCollectedTally(MIDDAY, 1, 1);

        QuestLifecycle.RepeatCheck refused = QuestLifecycle.repeatCheck(repeat, 0L, spent, MIDDAY);
        assertFalse(refused.available());
        assertEquals(QuestGates.REASON_PERIOD_SPENT, refused.reason());
        assertEquals(RepeatPeriod.nextBoundaryMs(repeat.reset(), MIDDAY), refused.offerableAtMs());

        long justAfterBoundary = RepeatPeriod.nextBoundaryMs(repeat.reset(), MIDDAY) + 1;
        assertTrue(QuestLifecycle.repeatCheck(repeat, 0L, spent, justAfterBoundary).available(),
                "the SAME stale record reads as nothing spent in the new window, so no sweep is"
                        + " needed to keep it honest");
    }

    @Test
    void aWindowOfThreeOnlyRefusesOnTheThird() {
        Repeat repeat = windowOnly(3);
        assertTrue(QuestLifecycle.repeatCheck(repeat, 0L,
                CompletionRecord.withoutCollectedTally(MIDDAY, 2, 2), MIDDAY).available());
        assertFalse(QuestLifecycle.repeatCheck(repeat, 0L,
                CompletionRecord.withoutCollectedTally(MIDDAY, 3, 3), MIDDAY).available());
    }

    @Test
    void aSpentLifetimeCapIsPermanent() {
        Repeat repeat = cappedAt(2);
        QuestLifecycle.RepeatCheck check = QuestLifecycle.repeatCheck(repeat, 0L,
                CompletionRecord.withoutCollectedTally(MIDDAY, 0, 2), MIDDAY);

        assertFalse(check.available());
        assertEquals(QuestGates.REASON_MAX_COMPLETIONS, check.reason());
        assertTrue(check.permanentlySpent());
        assertFalse(QuestLifecycle.repeatCheck(repeat, 0L, CompletionRecord.withoutCollectedTally(MIDDAY, 0, 2),
                MIDDAY + 3650 * DAY).available(), "no amount of waiting brings it back");
    }

    /**
     * The evaluator reads FINISHES, and the two tallies are what makes that testable at all: every
     * other fixture here is built through {@code withoutCollectedTally}, where the two numbers agree
     * and either reading would pass. A record whose runs are finished with the rewards still owing
     * pulls them apart, and the cap has to be spent all the same - a run somebody walked away from
     * without collecting still happened.
     */
    @Test
    void aLifetimeCapIsSpentByFinishesEvenWhenNothingWasCollected() {
        Repeat repeat = cappedAt(2);

        QuestLifecycle.RepeatCheck spent = QuestLifecycle.repeatCheck(repeat, 0L,
                new CompletionRecord(MIDDAY, 0, 2, 0), MIDDAY);
        assertFalse(spent.available(),
                "two runs finished spends a cap of two, whether or not anybody came back for either"
                        + " reward");
        assertEquals(QuestGates.REASON_MAX_COMPLETIONS, spent.reason());

        assertTrue(QuestLifecycle.repeatCheck(repeat, 0L,
                new CompletionRecord(MIDDAY, 0, 1, 1), MIDDAY).available(),
                "one run finished and collected leaves the second, so the refusal above is a real"
                        + " reading of a spent cap and not this evaluator refusing everything");
    }

    /**
     * The same for the calendar allowance. The window tally has no collected twin at all, so what
     * this pins is that a window whose runs are all still waiting to be collected is spent exactly
     * like one whose rewards were taken on the spot.
     */
    @Test
    void aCalendarAllowanceIsSpentByFinishesToo() {
        Repeat repeat = windowOnly(2);

        QuestLifecycle.RepeatCheck spent = QuestLifecycle.repeatCheck(repeat, 0L,
                new CompletionRecord(MIDDAY, 2, 2, 0), MIDDAY);
        assertFalse(spent.available(),
                "both runs this window allows were finished, with neither reward collected");
        assertEquals(QuestGates.REASON_PERIOD_SPENT, spent.reason());

        assertTrue(QuestLifecycle.repeatCheck(repeat, 0L,
                new CompletionRecord(MIDDAY, 1, 1, 0), MIDDAY).available(),
                "one finish leaves the second slot in the window, and no collection is needed to"
                        + " reach it");
    }

    @Test
    void theLifetimeCapOutranksASpentWindowWhichOutranksARunningCooldown() {
        Reset daily = new Reset(Reset.Period.DAILY, 0, DayOfWeek.MONDAY, 1);
        Repeat all = new Repeat(4 * HOUR, CooldownFrom.CLAIM, daily, 2);

        assertEquals(QuestGates.REASON_MAX_COMPLETIONS, QuestLifecycle.repeatCheck(all, MIDDAY,
                CompletionRecord.withoutCollectedTally(MIDDAY, 1, 2), MIDDAY).reason(),
                "telling somebody to come back in three hours for a quest they can never take"
                        + " again is the worse message");
        assertEquals(QuestGates.REASON_PERIOD_SPENT, QuestLifecycle.repeatCheck(all, MIDDAY,
                CompletionRecord.withoutCollectedTally(MIDDAY, 1, 1), MIDDAY).reason());
        assertEquals(QuestGates.REASON_ON_COOLDOWN, QuestLifecycle.repeatCheck(all, MIDDAY,
                CompletionRecord.withoutCollectedTally(MIDDAY - 2 * DAY, 1, 1), MIDDAY).reason());
    }

    @Test
    void aBackwardsClockAnswersTheFullWindowRatherThanNonsense() {
        Repeat repeat = cooldownOnly(4 * HOUR);
        assertEquals(4 * HOUR + HOUR,
                QuestLifecycle.cooldownRemainingMs(repeat, MIDDAY, MIDDAY - HOUR));
        assertFalse(QuestLifecycle.repeatCheck(repeat, MIDDAY, CompletionRecord.NONE, MIDDAY - HOUR)
                .available());
    }

    @Test
    void aStoredCompletedStatusReadsAsWhateverIsActuallyTrue() {
        Repeat repeat = cooldownOnly(4 * HOUR);
        assertEquals(QuestStatus.ON_COOLDOWN, QuestLifecycle.effectiveStatus(repeat,
                QuestStatus.COMPLETED, MIDDAY, CompletionRecord.NONE, MIDDAY + HOUR));
        assertEquals(QuestStatus.NOT_STARTED, QuestLifecycle.effectiveStatus(repeat,
                QuestStatus.COMPLETED, MIDDAY, CompletionRecord.NONE, MIDDAY + 4 * HOUR));
        assertEquals(QuestStatus.COMPLETED, QuestLifecycle.effectiveStatus(cappedAt(1),
                QuestStatus.COMPLETED, 0L, CompletionRecord.withoutCollectedTally(MIDDAY, 0, 1), MIDDAY + 4 * HOUR),
                "a spent lifetime cap has genuinely become a one-shot, and reads like one");
    }

    @Test
    void everyOtherStoredStatusPassesThroughUntouched() {
        Repeat repeat = cooldownOnly(4 * HOUR);
        for (QuestStatus stored : new QuestStatus[] {
                QuestStatus.NOT_STARTED, QuestStatus.ACTIVE, QuestStatus.COMPLETED_UNCLAIMED}) {
            assertEquals(stored, QuestLifecycle.effectiveStatus(repeat, stored, MIDDAY,
                    CompletionRecord.withoutCollectedTally(MIDDAY, 1, 1), MIDDAY + HOUR));
        }
    }

    @Test
    void offerableInMsTellsTheWholeTruth() {
        assertEquals(0L, QuestLifecycle.offerableInMs(Repeat.EXTERNALLY_GOVERNED, 0L,
                CompletionRecord.NONE, MIDDAY));
        assertEquals(3 * HOUR, QuestLifecycle.offerableInMs(cooldownOnly(4 * HOUR), MIDDAY,
                CompletionRecord.NONE, MIDDAY + HOUR));
        assertEquals(Long.MAX_VALUE, QuestLifecycle.offerableInMs(cappedAt(1), 0L,
                CompletionRecord.withoutCollectedTally(MIDDAY, 0, 1), MIDDAY));
    }
}
