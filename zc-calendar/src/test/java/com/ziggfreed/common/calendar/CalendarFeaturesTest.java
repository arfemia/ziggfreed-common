package com.ziggfreed.common.calendar;

import static com.ziggfreed.common.calendar.CalendarFixtures.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.calendar.asset.CalendarEventAsset;
import com.ziggfreed.common.calendar.asset.CalendarEventConfig;
import com.ziggfreed.common.factor.FeatureFlags;

/** Off means absent: every switch the calendar has is a feature the shared folds hide content on. */
class CalendarFeaturesTest {

    private final long[] now = {at("2026-10-02T12:00:00Z")};
    private final CalendarService service =
            new CalendarService(CalendarEventConfig.getInstance(), CalendarForces.getInstance());

    @BeforeEach
    void declare() {
        CalendarFixtures.reset();
        CalendarFixtures.loadDesignEvents();
        CalendarFeatures.declare(service);
        CalendarFeatures.declareEvents(service, () -> now[0]);
    }

    @AfterEach
    void forget() {
        FeatureFlags.reset();
        CalendarFixtures.reset();
    }

    private static Double feature(String id) {
        return FeatureFlags.read(CalendarFeatures.NAMESPACE, id);
    }

    @Test
    void theCalendarDeclaresItsNamespaceSoAGatedFoldHidesRatherThanLocks() {
        assertTrue(FeatureFlags.isFeatureFactor("ziggfreedcommon:feature"),
                "a shared fold lifts a top-level condition on a declared namespace onto the hide axis");
        assertEquals(1.0, feature("Calendar"));
    }

    @Test
    void anEventIsOnWhileSwitchedOnAndLiveOnlyBetweenItsDates() {
        assertEquals(1.0, feature("Hallows_Eve"));
        assertEquals(1.0, feature("Hallows_Eve_Live"));
        assertEquals(1.0, feature("Harvest_Moon"), "switched on before its nights come");
        assertEquals(0.0, feature("Harvest_Moon_Live"));
        now[0] = at("2026-10-30T22:00:00Z");
        assertEquals(1.0, feature("harvest_moon_live"), "read fresh on every gate, matched without regard to case");
    }

    @Test
    void theOwnersSwitchTurnsEveryFeatureOff() {
        CalendarEventConfig.getInstance().setGlobalEnabled(false);
        assertEquals(0.0, feature("Calendar"));
        assertEquals(0.0, feature("Hallows_Eve"));
        assertEquals(0.0, feature("Hallows_Eve_Live"));
    }

    @Test
    void anEventsOwnSwitchTurnsOnlyItsFeaturesOff() {
        CalendarEventConfig config = CalendarEventConfig.getInstance();
        config.mergeOwnerLayer(Map.of("hallows_eve",
                CalendarFixtures.event("Hallows_Eve", "{ \"Enabled\": false }", config.resolve("hallows_eve"))));
        assertEquals(0.0, feature("Hallows_Eve"));
        assertEquals(0.0, feature("Hallows_Eve_Live"));
        assertEquals(1.0, feature("Harvest_Moon"));
    }

    @Test
    void anEventWhoseFileWentAwayReadsOff() {
        CalendarFixtures.loadEvents(Map.of());
        assertEquals(0.0, feature("Hallows_Eve"));
        assertEquals(0.0, feature("Hallows_Eve_Live"));
    }

    @Test
    void anEventFileUnderAReservedIdNeverTakesOverAnotherSwitch() {
        FeatureFlags.register(CalendarFeatures.NAMESPACE, "Almanac", "almanac-stand-in", () -> true);
        CalendarFixtures.loadEvents(Map.of(
                "hallows_eve", CalendarFixtures.event("Hallows_Eve", CalendarFixtures.HALLOWS_EVE),
                "calendar", CalendarFixtures.event("Calendar", CalendarFixtures.HALLOWS_EVE),
                "almanac", CalendarFixtures.event("Almanac", CalendarFixtures.HALLOWS_EVE),
                "hallows_eve_live", CalendarFixtures.event("Hallows_Eve_Live", CalendarFixtures.HALLOWS_EVE)));
        CalendarFeatures.declareEvents(service, () -> now[0]);
        assertEquals(1.0, feature("Calendar"), "still the owner's switch over every event");
        assertEquals(1.0, feature("Almanac"), "still the Almanac's own switch");
        assertEquals(1.0, feature("Hallows_Eve_Live"), "still Hallows_Eve's running switch");
        assertTrue(CalendarEventAsset.isReservedId(CalendarFeatures.CALENDAR), "the reserved ids cover this class's own");
        assertTrue(CalendarEventAsset.isReservedId("Any_Event" + CalendarFeatures.LIVE_SUFFIX));
    }
}
