package com.ziggfreed.common.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.DayOfWeek;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.quest.Quest.Repeat;
import com.ziggfreed.common.quest.Quest.Repeat.CooldownFrom;
import com.ziggfreed.common.quest.Quest.Repeat.Reset;

/**
 * The ONE classification of how often a quest comes round, driven through every shape a repeat rule
 * can take. The numbers here are the two thresholds themselves and lengths either side of them,
 * which is the whole rule; none of them is balance data.
 */
class QuestCadenceTest {

    private static final long HOUR = 3_600_000L;
    private static final long DAY = 24 * HOUR;

    private static Repeat rolling(long cooldownMs) {
        return new Repeat(cooldownMs, CooldownFrom.CLAIM, null, 0);
    }

    private static Repeat calendar(long periodMs) {
        return new Repeat(0L, CooldownFrom.CLAIM, new Reset(periodMs, 0, DayOfWeek.MONDAY, 1), 0);
    }

    @Test
    void aOneShotHasNoCadence() {
        assertEquals(QuestCadence.NONE, QuestCadence.of(null));
    }

    @Test
    void anEmptyGroupIsRepeatable() {
        assertEquals(QuestCadence.REPEATABLE, QuestCadence.of(Repeat.EXTERNALLY_GOVERNED),
                "nothing holds it back, so it comes round on whatever clock offers it");
        assertEquals(QuestCadence.REPEATABLE, Repeat.EXTERNALLY_GOVERNED.cadence());
    }

    @Test
    void aCalendarDayAndARollingDayBothReadDaily() {
        assertEquals(QuestCadence.DAILY, QuestCadence.of(calendar(DAY)));
        assertEquals(QuestCadence.DAILY, QuestCadence.of(rolling(DAY)));
        assertEquals(QuestCadence.DAILY, QuestCadence.of(rolling(20 * HOUR)),
                "the threshold is inclusive, so a wait trimmed a little short still reads daily");
    }

    @Test
    void aShortClockReadsRepeatable() {
        assertEquals(QuestCadence.REPEATABLE, QuestCadence.of(rolling(2 * HOUR)));
        assertEquals(QuestCadence.REPEATABLE, QuestCadence.of(calendar(8 * HOUR)));
        assertEquals(QuestCadence.REPEATABLE, QuestCadence.of(rolling(20 * HOUR - 1)),
                "one millisecond under the daily threshold is not daily");
    }

    @Test
    void aWeekAndAnythingLongerReadWeekly() {
        assertEquals(QuestCadence.WEEKLY, QuestCadence.of(calendar(7 * DAY)));
        assertEquals(QuestCadence.WEEKLY, QuestCadence.of(rolling(7 * DAY)));
        assertEquals(QuestCadence.WEEKLY, QuestCadence.of(calendar(14 * DAY)), "a fortnight reads weekly");
        assertEquals(QuestCadence.WEEKLY, QuestCadence.of(calendar(21 * DAY)), "so do three weeks");
        assertEquals(QuestCadence.WEEKLY, QuestCadence.of(rolling(6 * DAY)),
                "six days is the weekly threshold, inclusive");
        assertEquals(QuestCadence.DAILY, QuestCadence.of(rolling(6 * DAY - 1)));
    }

    @Test
    void theLongerOfTheTwoClocksDecides() {
        Repeat dailyWindowWeeklyWait = new Repeat(7 * DAY, CooldownFrom.CLAIM,
                new Reset(DAY, 0, DayOfWeek.MONDAY, 1), 0);
        assertEquals(QuestCadence.WEEKLY, QuestCadence.of(dailyWindowWeeklyWait),
                "a player waits a week between goes, whatever the window says");

        Repeat weeklyWindowShortWait = new Repeat(2 * HOUR, CooldownFrom.CLAIM,
                new Reset(7 * DAY, 0, DayOfWeek.MONDAY, 1), 0);
        assertEquals(QuestCadence.WEEKLY, QuestCadence.of(weeklyWindowShortWait));
    }

    @Test
    void theInstanceReadAndTheStaticReadAgree() {
        for (Repeat repeat : new Repeat[] {rolling(2 * HOUR), rolling(DAY), calendar(8 * HOUR),
                calendar(DAY), calendar(14 * DAY), Repeat.EXTERNALLY_GOVERNED}) {
            assertEquals(QuestCadence.of(repeat), repeat.cadence());
        }
    }
}
