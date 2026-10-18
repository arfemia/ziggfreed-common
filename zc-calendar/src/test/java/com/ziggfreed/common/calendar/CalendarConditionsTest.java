package com.ziggfreed.common.calendar;

import static com.ziggfreed.common.calendar.CalendarFixtures.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.calendar.asset.CalendarEventConfig;
import com.ziggfreed.common.calendar.asset.CalendarEventValidator;
import com.ziggfreed.common.calendar.moon.MoonSource;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * An event live by a condition through the service: a Moon over the default world's phase, composites over a dated
 * event and the moon, a loop refused, and a condition never read as a run.
 */
class CalendarConditionsTest {

    private static final long OCTOBER = at("2026-10-02T12:00:00Z");
    private static final long NOVEMBER = at("2026-11-20T12:00:00Z");

    /** The default world's phase, as the last moon change said it; -1 is a world that has said nothing yet. */
    private final int[] phase = {0};

    private final MoonSource moons = world -> phase[0] < 0 || (world != null && !world.isBlank())
            ? null : new MoonSource.Reading("default", phase[0], 5);

    private final CalendarService service =
            new CalendarService(CalendarEventConfig.getInstance(), CalendarForces.getInstance(), moons);

    @BeforeEach
    void load() {
        CalendarFixtures.reset();
        CalendarFixtures.loadEvents(Map.of(
                "hallows_eve", CalendarFixtures.event("Hallows_Eve", CalendarFixtures.HALLOWS_EVE),
                "full_moon", CalendarFixtures.event("Full_Moon", "{ \"Moon\": { \"MoonPhaseRange\": [0, 1] } }"),
                "hallowed_nights", CalendarFixtures.event("Hallowed_Nights",
                        "{ \"Live\": { \"AnyOf\": [\"Hallows_Eve\", \"Full_Moon\"] } }"),
                "restless_moon", CalendarFixtures.event("Restless_Moon",
                        "{ \"Live\": { \"AllOf\": [\"Full_Moon\"], \"NoneOf\": [\"Hallows_Eve\"] } }")));
    }

    @AfterEach
    void reset() {
        CalendarFixtures.reset();
    }

    @Test
    void aMoonEventIsLiveOnlyInItsPhasesOfTheDefaultWorld() {
        boolean[] live = new boolean[5];
        for (int p = 0; p < 5; p++) {
            phase[0] = p;
            live[p] = service.isLive("Full_Moon", NOVEMBER);
        }
        assertTrue(live[0] && live[1], "[0, 1]: the full moon and the night after");
        assertFalse(live[2] || live[3] || live[4]);
        phase[0] = -1;
        assertFalse(service.isLive("Full_Moon", NOVEMBER), "a world that has said no phase yet keeps it waiting");
        assertTrue(service.isSwitchedOn("Full_Moon"), "waiting is switched on, never absent");
    }

    @Test
    void aConditionIsNeverARun() {
        phase[0] = 0;
        assertTrue(service.isLive("Full_Moon", OCTOBER));
        assertNull(service.live("Full_Moon", OCTOBER), "no run, so nothing counts one");
        assertFalse(service.liveAll(OCTOBER).containsKey("full_moon"));
        assertFalse(service.liveAll(OCTOBER).containsKey("hallowed_nights"));
        assertEquals(List.of(), service.history("Full_Moon", OCTOBER));
        assertFalse(service.isEnabled("Full_Moon"), "absent to every reader of runs: the Almanac lists nothing");
        assertNull(service.firstYear("Full_Moon"), "no year to keep a copy for");
        assertNull(service.next("Full_Moon", OCTOBER));
        assertEquals(Set.of("full_moon", "hallowed_nights"), service.liveConditions(OCTOBER),
                "the conditions the tick follows, Restless_Moon kept out by Hallow's Eve");
    }

    @Test
    void compositesFollowTheSeasonAndTheMoon() {
        // Hallowed_Nights is AnyOf (Hallows_Eve, Full_Moon); Restless_Moon is Full_Moon NoneOf Hallows_Eve.
        Object[][] table = {
                // when,    moon phase, Hallowed_Nights, Restless_Moon
                {OCTOBER, 0, true, false},
                {OCTOBER, 3, true, false},
                {NOVEMBER, 0, true, true},
                {NOVEMBER, 3, false, false},
        };
        for (Object[] row : table) {
            long when = (long) row[0];
            phase[0] = (int) row[1];
            String at = (when == OCTOBER ? "October" : "November") + ", phase " + phase[0];
            assertEquals(row[2], service.isLive("Hallowed_Nights", when), "Hallowed_Nights, " + at);
            assertEquals(row[3], service.isLive("Restless_Moon", when), "Restless_Moon, " + at);
        }
    }

    @Test
    void aForceBeatsTheMoonAndReachesEveryCompositeOnIt() {
        phase[0] = 3;
        assertTrue(service.forceConditionOn("Full_Moon"));
        assertTrue(service.isLive("Full_Moon", NOVEMBER), "forced on: live until cleared, whatever the moon");
        assertTrue(service.isLive("Restless_Moon", NOVEMBER), "a composite reads its member's force");
        CalendarForces.getInstance().forceOff("Full_Moon");
        phase[0] = 0;
        assertFalse(service.isLive("Full_Moon", NOVEMBER), "forced off: never live");
        CalendarForces.getInstance().clear("Full_Moon");
        assertTrue(service.isLive("Full_Moon", NOVEMBER));
        assertFalse(service.forceConditionOn("Hallows_Eve"), "an event on dates takes a run when forced");
    }

    @Test
    void aLoopIsRefusedAndTheAuditSaysSo() {
        CalendarEventConfig.getInstance().mergeOwnerLayer(Map.of(
                "loop_a", CalendarFixtures.event("Loop_A", "{ \"Live\": { \"AnyOf\": [\"Loop_B\", \"Full_Moon\"] } }"),
                "loop_b", CalendarFixtures.event("Loop_B", "{ \"Live\": { \"AnyOf\": [\"Loop_A\"] } }"),
                "ghost", CalendarFixtures.event("Ghost", "{ \"Live\": { \"AnyOf\": [\"No_Such_Event\"] } }")));
        phase[0] = 0;
        assertFalse(service.isLive("Loop_A", NOVEMBER), "refused, though Full_Moon alone would make it live");
        assertFalse(service.isLive("Loop_B", NOVEMBER));
        assertEquals(List.of("loop_a", "loop_b", "loop_a"), service.loopOf("Loop_A"));
        assertFalse(service.isLive("Ghost", NOVEMBER), "an event the server lacks reads as not live");

        List<Finding> findings = CalendarEventValidator.audit(CalendarEventConfig.getInstance().all().values(),
                List.of(), id -> true, key -> true);
        List<Finding> cycles = findings.stream().filter(f -> f.code().equals(CalendarEventValidator.LIVE_CYCLE))
                .toList();
        assertEquals(List.of("loop_a", "loop_b"), cycles.stream().map(Finding::sourceId).sorted().toList(),
                "one finding for each event the loop refuses");
        assertTrue(cycles.stream().allMatch(f -> f.severity() == Severity.WARNING));
        List<Finding> unknown = findings.stream()
                .filter(f -> f.code().equals(CalendarEventValidator.LIVE_UNKNOWN_EVENT)).toList();
        assertEquals(List.of("ghost"), unknown.stream().map(Finding::sourceId).toList());
        assertEquals(Severity.WARNING, unknown.get(0).severity(), "an unknown id is a warning, never an error");
    }
}
