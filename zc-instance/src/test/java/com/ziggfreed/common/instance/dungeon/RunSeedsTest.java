package com.ziggfreed.common.instance.dungeon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * The daily seed ({@link RunSeeds#daily}): every party on one day shares it, the next day brings another, and the day
 * turns at midnight UTC, the window a dungeon's daily rewards count in. Untagged: nothing here builds an engine item.
 */
class RunSeedsTest {

    private static final long NOV_15_MORNING = Instant.parse("2026-11-15T08:00:00Z").toEpochMilli();
    private static final long NOV_15_NIGHT = Instant.parse("2026-11-15T23:59:59Z").toEpochMilli();
    private static final long NOV_16_JUST_AFTER = Instant.parse("2026-11-16T00:00:00Z").toEpochMilli();

    @Test
    void theDailySeedIsOneSeedForTheDayAndAnotherTheNext() {
        long day = RunSeeds.dayOf(NOV_15_MORNING);
        assertEquals(day, RunSeeds.dayOf(NOV_15_NIGHT), "the whole UTC day is one day");
        assertEquals(day + 1, RunSeeds.dayOf(NOV_16_JUST_AFTER), "midnight UTC starts the next");

        long first = RunSeeds.daily(day, "MyPack_Deep_Daily");
        assertEquals(first, RunSeeds.daily(day, "MyPack_Deep_Daily"), "two calls on one date give one seed");
        assertEquals(first, RunSeeds.daily(day, " mypack_deep_daily "), "the id is matched without regard to case");
        assertNotEquals(first, RunSeeds.daily(day + 1, "MyPack_Deep_Daily"), "the next day gives another");
        assertNotEquals(first, RunSeeds.daily(day, "MyPack_Crypt_Daily"), "another dungeon gives another");
    }
}
