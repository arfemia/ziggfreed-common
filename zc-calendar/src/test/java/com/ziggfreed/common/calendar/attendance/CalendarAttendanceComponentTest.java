package com.ziggfreed.common.calendar.attendance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;

/** The per-player attendance record: case-blind, ordered on read, stable on save, and strict about its delimiters. */
class CalendarAttendanceComponentTest {

    @Test
    void aRunAndALookupMeetWhateverTheCasing() {
        CalendarAttendanceComponent record = new CalendarAttendanceComponent();
        assertTrue(record.markAttended("Hallows_Eve", 2026));
        assertTrue(record.hasAttended("hallows_eve", 2026));
        assertFalse(record.markAttended("HALLOWS_EVE", 2026), "the same run twice is one attendance");
        assertFalse(record.hasAttended("hallows_eve", 2027));
    }

    @Test
    void yearsAndEventsReadBackInOrder() {
        CalendarAttendanceComponent record = new CalendarAttendanceComponent();
        record.markAttended("Hallows_Eve", 2027);
        record.markAttended("Hallows_Eve", 2026);
        record.markAttended("Harvest_Moon", 2026);
        assertEquals(List.of(2026, 2027), record.yearsAttended("hallows_eve"));
        assertEquals(List.of("hallows_eve", "harvest_moon"), record.eventsAttended());
    }

    @Test
    void theSaveFormatRoundTripsAndIsStable() {
        CalendarAttendanceComponent record = new CalendarAttendanceComponent();
        record.markAttended("Harvest_Moon", 2026);
        record.markAttended("Hallows_Eve", 2026);
        assertEquals("hallows_eve@2026|harvest_moon@2026", record.save());
        CalendarAttendanceComponent reloaded = new CalendarAttendanceComponent();
        reloaded.load(record.save());
        assertTrue(reloaded.hasAttended("harvest_moon", 2026));
        assertEquals(record.save(), reloaded.save());
    }

    @Test
    void aRestartReadsTheRecordBackThroughTheEngineCodec() throws IOException {
        CalendarAttendanceComponent record = new CalendarAttendanceComponent();
        record.markAttended("Hallows_Eve", 2026);
        ExtraInfo info = new ExtraInfo();
        CalendarAttendanceComponent restarted =
                CalendarAttendanceComponent.CODEC.decode(CalendarAttendanceComponent.CODEC.encode(record, info), info);
        assertTrue(restarted.hasAttended("hallows_eve", 2026), "the record the player was saved with");
        CalendarAttendanceComponent saved = CalendarAttendanceComponent.CODEC.decodeJson(
                RawJsonReader.fromJsonString("{\"Attended\":\"hallows_eve@2026\"}"), new ExtraInfo());
        assertTrue(saved.hasAttended("hallows_eve", 2026), "the saved key is Attended");
        CalendarAttendanceComponent before = CalendarAttendanceComponent.CODEC.decodeJson(
                RawJsonReader.fromJsonString("{}"), new ExtraInfo());
        assertTrue(before.eventsAttended().isEmpty(), "a player saved before the calendar has attended nothing");
    }

    @Test
    void aSaveInAnotherCasingReadsAsTheSameRuns() {
        CalendarAttendanceComponent record = new CalendarAttendanceComponent();
        record.load("Hallows_Eve@2026");
        assertTrue(record.hasAttended("hallows_eve", 2026));
        assertFalse(record.markAttended("HALLOWS_EVE", 2026), "a hand-edited entry is the same run, credited once");
        assertEquals(List.of("hallows_eve"), record.eventsAttended());
    }

    @Test
    void anIdCarryingAReservedCharacterIsRefused() {
        CalendarAttendanceComponent record = new CalendarAttendanceComponent();
        assertFalse(record.markAttended("bad|event", 2026));
        assertFalse(record.markAttended("bad@event", 2026));
        assertTrue(record.eventsAttended().isEmpty());
    }

    @Test
    void aCloneIsADeepCopy() {
        CalendarAttendanceComponent record = new CalendarAttendanceComponent();
        record.markAttended("Hallows_Eve", 2026);
        CalendarAttendanceComponent copy = record.clone();
        copy.markAttended("Harvest_Moon", 2026);
        assertFalse(record.hasAttended("harvest_moon", 2026));
    }

    @Test
    void aLaterRunOfAYearIsItsOwnEntryAndASaveFromBeforeIsRunOne() {
        CalendarAttendanceComponent record = new CalendarAttendanceComponent();
        record.load("spring_fair@2026");
        assertTrue(record.hasAttended("Spring_Fair", 2026, 1), "an entry with no number is run 1");
        assertFalse(record.markAttended("Spring_Fair", 2026, 1), "so it is not credited twice");
        assertTrue(record.markAttended("Spring_Fair", 2026, 2));
        assertFalse(record.markAttended("spring_fair", 2026, 2));
        assertEquals("spring_fair@2026|spring_fair@2026#2", record.save(),
                "run 1 saves as before; any other run adds #n");
        assertEquals(2, record.runsAttended("Spring_Fair", 2026));
        assertEquals(List.of(2026), record.yearsAttended("Spring_Fair"), "one year, however many of its runs");
        assertTrue(record.hasAttended("Spring_Fair", 2026));
        assertFalse(record.hasAttended("Spring_Fair", 2026, 3));
        assertEquals(List.of("spring_fair"), record.eventsAttended());
        CalendarAttendanceComponent reloaded = new CalendarAttendanceComponent();
        reloaded.load(record.save());
        assertEquals(2, reloaded.runsAttended("Spring_Fair", 2026), "a restart reads both runs back");
    }

    @Test
    void aRunWrittenTwiceByHandIsOneRun() {
        CalendarAttendanceComponent record = new CalendarAttendanceComponent();
        record.load("spring_fair@2026|spring_fair@2026#1|spring_fair@2026#02|spring_fair@2026#2");
        assertEquals(2, record.runsAttended("Spring_Fair", 2026), "#1 is run 1 and #02 is run 2: two runs, not four");
        assertTrue(record.hasAttended("Spring_Fair", 2026, 2));
        assertFalse(record.markAttended("Spring_Fair", 2026, 2),
                "a run already written, however it was written, is not credited again");
        assertFalse(record.markAttended("Spring_Fair", 2026, 1));
        CalendarAttendanceComponent handWritten = new CalendarAttendanceComponent();
        handWritten.load("spring_fair@2026#09");
        assertTrue(handWritten.hasAttended("Spring_Fair", 2026, 9), "run 9 written as #09");
        assertFalse(handWritten.markAttended("Spring_Fair", 2026, 9));
        assertEquals(1, handWritten.runsAttended("Spring_Fair", 2026));
    }
}
