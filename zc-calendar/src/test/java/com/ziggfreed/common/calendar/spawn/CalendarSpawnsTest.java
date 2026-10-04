package com.ziggfreed.common.calendar.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.calendar.CalendarFixtures;
import com.ziggfreed.common.calendar.asset.CalendarSpawnConfig;
import com.ziggfreed.common.calendar.tick.CalendarTick;

/** The spawn rules follow the running events: written once, rewritten in place, retired, never removed. */
class CalendarSpawnsTest {

    private final List<String> sent = new ArrayList<>();

    @BeforeEach
    void setUp() {
        CalendarFixtures.reset();
        CalendarSpawnConfig.getInstance().mergePackLayer(Map.of(
                "hallows_eve_ghouls_base", CalendarFixtures.spawn("Hallows_Eve_Ghouls_Base", CalendarSpawnPlanTest.GHOULS),
                "harvest_moon_ghouls", CalendarFixtures.spawn("Harvest_Moon_Ghouls", CalendarSpawnPlanTest.GHOULS_HARVEST)));
        CalendarSpawns.useSinkForTests((rule, json) -> {
            sent.add(rule + "=" + json);
            return true;
        });
    }

    @AfterEach
    void tearDown() {
        CalendarFixtures.reset();
    }

    @Test
    void theRunningEventsRuleIsWrittenOnceAndNotAgainUntilSomethingChanges() {
        CalendarSpawns.reconcile(Set.of("hallows_eve"));
        CalendarSpawns.reconcile(Set.of("hallows_eve"));
        assertEquals(1, sent.size());
        assertTrue(sent.get(0).startsWith("hallows_eve_ghouls="));
    }

    @Test
    void harvestMoonRewritesTheSameRuleAndHandsItBack() {
        CalendarSpawns.reconcile(Set.of("hallows_eve"));
        CalendarSpawns.reconcile(Set.of("hallows_eve", "harvest_moon"));
        CalendarSpawns.reconcile(Set.of("hallows_eve"));
        assertEquals(3, sent.size());
        assertTrue(sent.get(1).contains("\"Weight\":30"));
        assertTrue(sent.get(2).contains("\"Weight\":10"));
        assertTrue(sent.stream().allMatch(line -> line.startsWith("hallows_eve_ghouls=")),
                "one rule id the whole way: the engine rebuilds it in place");
    }

    @Test
    void whenTheEventEndsTheRuleIsRetiredToZeroWeight() {
        CalendarSpawns.reconcile(Set.of("hallows_eve"));
        CalendarSpawns.reconcile(Set.of());
        assertEquals(2, sent.size());
        assertTrue(sent.get(1).contains("\"MoonPhaseWeightModifiers\":[0]"));
        CalendarSpawns.reconcile(Set.of());
        assertEquals(2, sent.size(), "a retired rule is not written again");
    }

    @Test
    void aRuleTheSinkRefusedIsTriedAgainNextTime() {
        CalendarSpawns.useSinkForTests((rule, json) -> false);
        CalendarSpawns.reconcile(Set.of("hallows_eve"));
        assertTrue(CalendarSpawns.written().isEmpty());
        CalendarSpawns.useSinkForTests((rule, json) -> {
            sent.add(rule);
            return true;
        });
        CalendarSpawns.reconcile(Set.of("hallows_eve"));
        assertEquals(List.of("hallows_eve_ghouls"), sent);
    }

    @Test
    void aTickHandsOverItsLiveSet() {
        CalendarSpawns.onTick(new CalendarTick(0L, false, Set.of("hallows_eve"), List.of(), List.of()));
        assertEquals(1, sent.size());
    }

    @Test
    void nothingIsWrittenForAnEventNoFileRides() {
        CalendarSpawns.reconcile(Set.of("spring_fair"));
        assertTrue(sent.isEmpty());
    }
}
