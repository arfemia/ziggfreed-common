package com.ziggfreed.common.calendar.attendance;

import static com.ziggfreed.common.calendar.CalendarFixtures.waitsFor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.calendar.CalendarFixtures;
import com.ziggfreed.common.calendar.CalendarRuntime;
import com.ziggfreed.common.calendar.asset.CalendarEventAsset;
import com.ziggfreed.common.calendar.asset.CalendarEventConfig;
import com.ziggfreed.common.calendar.tick.CalendarTick;
import com.ziggfreed.common.occurrence.Occurrence;

/** Who is credited, when, and how often: once per player per run, persisted, never at a boot catch-up. */
class CalendarAttendanceTest {

    private static final Occurrence EVE_2026 = new Occurrence("hallows_eve", 2026, 1L, 2L);
    private static final Occurrence MOON_2026 = new Occurrence("harvest_moon", 2026, 1L, 2L);

    @AfterEach
    void reset() {
        CalendarFixtures.reset();
    }

    @Test
    void aPlayerIsCreditedOncePerRun() {
        CalendarAttendanceComponent record = new CalendarAttendanceComponent();
        assertEquals(List.of(EVE_2026, MOON_2026), CalendarAttendance.credit(record, List.of(EVE_2026, MOON_2026)));
        assertTrue(CalendarAttendance.credit(record, List.of(EVE_2026)).isEmpty(),
                "coming back during the same run credits nothing and shows no banner");
        Occurrence next = new Occurrence("hallows_eve", 2027, 3L, 4L);
        assertEquals(List.of(next), CalendarAttendance.credit(record, List.of(next)), "next year's run is a new run");
    }

    @Test
    void theCreditSurvivesASaveSoARestartNeverRepeatsTheBanner() {
        CalendarAttendanceComponent record = new CalendarAttendanceComponent();
        CalendarAttendance.credit(record, List.of(EVE_2026));
        CalendarAttendanceComponent afterRestart = new CalendarAttendanceComponent();
        afterRestart.load(record.save());
        assertTrue(CalendarAttendance.credit(afterRestart, List.of(EVE_2026)).isEmpty());
    }

    @Test
    void onlyARealStartCreditsEveryoneOnline() {
        CalendarTick tick = new CalendarTick(0L, false, Set.of("hallows_eve", "harvest_moon"),
                List.of(new CalendarTick.Started(EVE_2026, true), new CalendarTick.Started(MOON_2026, false)), List.of());
        assertEquals(List.of(MOON_2026), CalendarAttendance.freshStarts(tick),
                "a run caught up at boot began while nobody was here");
    }

    @Test
    void theAttendanceStepReadsAsASentenceInEnglish() {
        Set<String> english = CalendarFixtures.englishKeys("ziggfreedcommon.calendar.lang");
        assertTrue(english.contains("objective.calendar_attended"));
        assertTrue(english.contains("objective.calendar_attended.any"));
    }

    /**
     * The owner file's fold empties the owner layer before it fills it again, so a read landing in between
     * would find an event the owner switched off running from its pack file, and a player entering a world
     * then would be credited for a run that is not there, for good. This thread plays that fold, holding the
     * calendar's lock with the owner layer emptied; the read a player entering a world makes waits for it
     * and reads it whole.
     */
    @Test
    void aPlayerEnteringHalfwayThroughAnOwnerReloadIsNeverCreditedForAnEventSwitchedOff()
            throws InterruptedException {
        CalendarFixtures.reset();
        CalendarFixtures.loadDesignEvents();
        CalendarEventConfig config = CalendarEventConfig.getInstance();
        Map<String, CalendarEventAsset> eveSwitchedOff = Map.of("hallows_eve",
                CalendarFixtures.event("Hallows_Eve", "{ \"Enabled\": false }", config.resolve("hallows_eve")));
        config.mergeOwnerLayer(eveSwitchedOff);
        long october = CalendarFixtures.at("2026-10-02T12:00:00Z");
        List<List<Occurrence>> read = new ArrayList<>();
        Thread entering = new Thread(() -> read.add(CalendarAttendance.runningAt(october)), "calendar-test-entering");
        Object lock = CalendarRuntime.service().lock();
        boolean waited;
        synchronized (lock) {
            config.mergeOwnerLayer(Map.of());
            entering.start();
            waited = waitsFor(entering, lock);
            config.mergeOwnerLayer(eveSwitchedOff);
        }
        assertTrue(entering.join(Duration.ofSeconds(5)), "the read ran once the reload let go");
        assertEquals(List.of(List.of()), read,
                "nothing runs: the owner switched Hallows_Eve off, and Harvest_Moon is between its dates");
        assertTrue(waited, "the read waited for the lock the reload folds under");
    }
}
