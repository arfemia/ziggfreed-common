package com.ziggfreed.common.settings.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.ui.hud.settings.HudSettingsDeps;
import com.ziggfreed.common.ui.route.DestinationType;
import com.ziggfreed.common.ui.route.Destinations;

/** The server's HUD layout opens from the Settings tab, so its Back returns there unless a consumer says otherwise. */
class BackToSettingsTest {

    private static final AtomicInteger OPENED = new AtomicInteger();

    @BeforeEach
    void claim() {
        Destinations.clearForTests();
        OPENED.set(0);
        Destinations.register("test", DestinationType.of(SettingsDestinations.TYPE, SettingsDestinations.Settings.class,
                SettingsDestinations.Settings.CODEC, (d, ctx) -> OPENED.incrementAndGet() > 0));
    }

    @AfterEach
    void clear() {
        Destinations.clearForTests();
    }

    @Test
    void theHudLayoutsBackReturnsToTheSettingsTab() {
        assertSame(HudSettingsDeps.TO_SETTINGS, HudSettingsDeps.DEFAULTS.back());
        assertSame(HudSettingsDeps.TO_SETTINGS, HudSettingsDeps.builder().back(null).build().back());
        assertTrue(HudSettingsDeps.DEFAULTS.backGuarded(null, null, null));
        assertEquals(1, OPENED.get());
    }

    @Test
    void withNothingToOpenBackClosesThePage() {
        Destinations.clearForTests();

        assertFalse(HudSettingsDeps.DEFAULTS.backGuarded(null, null, null));
    }
}
