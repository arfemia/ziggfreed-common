package com.ziggfreed.common.ui.hud.bar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The seam's registry: a source is found under its namespace whatever the case it was written in, a
 * second registration replaces the first, an unfilled namespace answers nothing rather than
 * throwing, and a value id splits into the namespace the registry is keyed by and the local part
 * a source is handed.
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

    @Test
    void aValueIdSplitsAtItsFirstColonAndFoldsTheNamespace() {
        assertEquals("mymod", HudBarSources.namespaceOf("  MyMod:skill/WOOD:cutting "),
                "the namespace is folded lower, so a registration matches by name");
        assertEquals("skill/WOOD:cutting", HudBarSources.localIdOf("  MyMod:skill/WOOD:cutting "),
                "the local part keeps its case and its own colons");

        assertNull(HudBarSources.namespaceOf("nonamespace"));
        assertNull(HudBarSources.localIdOf("mymod:"));
        assertNull(HudBarSources.namespaceOf(":wood"));
        assertNull(HudBarSources.namespaceOf(null));
        assertNull(HudBarSources.localIdOf(null));
    }

    @Test
    void anItemRowIdIsNotAValueIdAnySourceIsAskedFor() {
        String rowId = HudBars.itemRowId("Wood_Plank");
        assertEquals(HudBars.ITEM_ROW_PREFIX, HudBarSources.namespaceOf(rowId) + ":",
                "an item row sits under the library's own prefix, which no consumer registers");
        assertFalse(HudBarSources.isFilled(HudBarSources.namespaceOf(rowId)));
    }
}
