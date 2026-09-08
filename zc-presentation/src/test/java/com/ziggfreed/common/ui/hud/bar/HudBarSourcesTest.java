package com.ziggfreed.common.ui.hud.bar;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The seam's registry: a source is found under its namespace whatever the case it was written in, a
 * second registration replaces the first, and an unfilled namespace answers nothing rather than
 * throwing.
 */
class HudBarSourcesTest {

    @BeforeEach
    @AfterEach
    void clear() {
        HudBarSources.clearForTests();
    }

    @Test
    void aSourceIsFoundUnderItsNamespaceCaseInsensitively() {
        HudBarSource source = (playerRef, localId) -> null;
        HudBarSources.register("MyMod", source);

        assertSame(source, HudBarSources.resolve("mymod"));
        assertSame(source, HudBarSources.resolve(" MYMOD "));
        assertTrue(HudBarSources.isFilled("mymod"));
        assertFalse(HudBarSources.isFilled("othermod"));
        assertNull(HudBarSources.resolve(null));
    }

    @Test
    void aSecondRegistrationReplacesTheFirstAndUnregisterForgetsIt() {
        HudBarSource first = (playerRef, localId) -> null;
        HudBarSource second = (playerRef, localId) -> HudBarSource.Reading.FULL;
        HudBarSources.register("mymod", first);
        HudBarSources.register("mymod", second);
        assertSame(second, HudBarSources.resolve("mymod"));

        HudBarSources.unregister("mymod");
        assertNull(HudBarSources.resolve("mymod"));
        assertFalse(HudBarSources.isFilled("mymod"));
    }
}
