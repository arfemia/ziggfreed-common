package com.ziggfreed.common.objectives.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.calendar.event.CalendarEventEndedEvent;
import com.ziggfreed.common.calendar.event.CalendarEventStartedEvent;

/**
 * A calendar start or end re-reads every placement: each transition asks for a sweep (a resumed start
 * and a switch-off included, the two a date never produces), and the sweep reaches every live world,
 * one world's failure costing only itself. The production sweep (the universe's worlds, the reconciler)
 * is the in-game smoke's: a unit JVM never touches the universe.
 */
class CalendarPlacementSweepTest {

    private final List<String> asked = new ArrayList<>();

    @AfterEach
    void restoreTheSweeper() {
        CalendarPlacementSweep.useSweeperForTests(null);
    }

    @Test
    void everyStartAsksForASweepResumedOrNot() {
        CalendarPlacementSweep.useSweeperForTests(asked::add);

        CalendarPlacementSweep.onStarted(new CalendarEventStartedEvent("harvest_moon", 2026, 10L, 20L, false, 10L));
        CalendarPlacementSweep.onStarted(new CalendarEventStartedEvent("spring_fair", 2026, 1L, 30L, true, 5L));

        assertEquals(List.of("harvest_moon started", "spring_fair is running (resumed at boot)"), asked);
    }

    @Test
    void everyEndAsksForASweepSwitchedOffOrNot() {
        CalendarPlacementSweep.useSweeperForTests(asked::add);

        CalendarPlacementSweep.onEnded(new CalendarEventEndedEvent("harvest_moon", 2026, false, 20L));
        CalendarPlacementSweep.onEnded(new CalendarEventEndedEvent("spring_fair", 2026, true, 21L));

        assertEquals(List.of("harvest_moon ended", "spring_fair was switched off"), asked);
    }

    @Test
    void everyLiveWorldIsSweptAndADeadOneIsSkipped() {
        List<String> swept = new ArrayList<>();

        int count = CalendarPlacementSweep.sweepEach(List.of("default", "closed_instance", "temple_1"),
                world -> !world.equals("closed_instance"), swept::add);

        assertEquals(2, count);
        assertEquals(List.of("default", "temple_1"), swept);
    }

    @Test
    void aWorldThatFailsCostsOnlyItself() {
        List<String> swept = new ArrayList<>();

        int count = CalendarPlacementSweep.sweepEach(List.of("default", "broken", "temple_1"), world -> true,
                world -> {
                    if (world.equals("broken")) {
                        throw new IllegalStateException("its task queue is closed");
                    }
                    swept.add(world);
                });

        assertEquals(2, count);
        assertEquals(List.of("default", "temple_1"), swept);
    }

    @Test
    void aMissingWorldOrOneWhoseLivenessCannotBeReadIsSkipped() {
        List<String> swept = new ArrayList<>();

        int count = CalendarPlacementSweep.sweepEach(Arrays.asList("default", null, "torn_down"),
                world -> {
                    if (world.equals("torn_down")) {
                        throw new IllegalStateException("gone");
                    }
                    return true;
                }, swept::add);

        assertEquals(1, count);
        assertEquals(List.of("default"), swept);
    }
}
