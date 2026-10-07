package com.ziggfreed.common.season;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.factor.FeatureFlags;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * The one reader of "live": a blank season is all year; a named one reads the calendar's
 * {@code <Season>_Live} feature afresh on every look, and anything but a definite 1 hides. An id no
 * loaded event declares is a warning for an audit, and the content stays hidden.
 */
class SeasonGateTest {

    private AtomicBoolean harvestLive;

    @BeforeEach
    void declareTheCalendarsSwitch() {
        harvestLive = new AtomicBoolean(false);
        FeatureFlags.register(SeasonGate.NAMESPACE, "Harvest_Feast_Live", "test", harvestLive::get);
    }

    @AfterEach
    void forgetDeclaredFeatures() {
        FeatureFlags.reset();
    }

    @Test
    void noSeasonIsAllYear() {
        assertTrue(SeasonGate.live(null));
        assertTrue(SeasonGate.live(""));
        assertTrue(SeasonGate.live("   "));
        assertFalse(SeasonGate.isSeasonal("  "));
        assertNull(SeasonGate.normalize("  "));
        assertEquals("Harvest_Feast", SeasonGate.normalize(" Harvest_Feast "));
    }

    @Test
    void aSeasonReadsItsLiveFeatureOnEveryLook() {
        assertFalse(SeasonGate.live("Harvest_Feast"), "the event is not running");
        harvestLive.set(true);
        assertTrue(SeasonGate.live("Harvest_Feast"), "the same call answers the new state, nothing cached");
        assertTrue(SeasonGate.live("harvest_feast"), "matched without regard to case, as a feature table is");
        assertTrue(SeasonGate.live("  Harvest_Feast "), "an author's spacing is not part of the id");
        harvestLive.set(false);
        assertFalse(SeasonGate.live("Harvest_Feast"));
    }

    @Test
    void theFeatureIsTheCalendarsRunningSwitchInItsNamespace() {
        assertEquals("ziggfreedcommon", SeasonGate.NAMESPACE);
        assertEquals("Harvest_Feast_Live", SeasonGate.featureOf("Harvest_Feast"));
        assertEquals("Harvest_Feast_Live", SeasonGate.featureOf(" Harvest_Feast "));
    }

    @Test
    void anIdNoLoadedEventDeclaresIsUnknownStaysHiddenAndIsAWarning() {
        assertFalse(SeasonGate.live("Harvest_Faest"), "the feature reads 0, so the content stays hidden");
        assertFalse(SeasonGate.known("Harvest_Faest"));
        assertTrue(SeasonGate.known("Harvest_Feast"));
        assertTrue(SeasonGate.known(null), "all year needs no event");

        List<Finding> out = new ArrayList<>();
        SeasonGate.checkKnown(out, "quest", "Harvest_Faest", "harvest_feast_pies");
        SeasonGate.checkKnown(out, "quest", "Harvest_Feast", "harvest_feast_turkey");
        SeasonGate.checkKnown(out, "quest", null, "all_year");

        assertEquals(1, out.size(), out.toString());
        Finding unknown = out.get(0);
        assertEquals(Severity.WARNING, unknown.severity(), "an unknown id is a warning, never an error");
        assertEquals(SeasonGate.UNKNOWN_SEASON, unknown.code());
        assertEquals("quest", unknown.domain());
        assertEquals("harvest_feast_pies", unknown.sourceId());
        assertTrue(unknown.message().contains("Harvest_Faest"), unknown.message());
    }

    @Test
    void withNoCalendarAtAllASeasonHidesAndAllYearStays() {
        FeatureFlags.reset();

        assertFalse(SeasonGate.live("Harvest_Feast"), "an undeclared namespace reads null, and null hides");
        assertTrue(SeasonGate.live(null));
    }
}
