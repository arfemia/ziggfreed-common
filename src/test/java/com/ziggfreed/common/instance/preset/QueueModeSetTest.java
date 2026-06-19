package com.ziggfreed.common.instance.preset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Unit-tests the resolved {@link QueueModeSet}: defaults, null-safe authoring overlay
 * (only-author-what-you-override), enabled filtering + order sorting, and per-mode lookup.
 * Same package so it can reach the package-private {@code from} + the asset's protected
 * authoring fields.
 */
class QueueModeSetTest {

    @Test
    void fallbackEnablesAllThreeInOrdinalOrderWithNoBakedIcon() {
        QueueModeSet d = QueueModeSet.fallback();
        assertTrue(d.publicMode().enabled());
        assertTrue(d.partyMode().enabled());
        assertTrue(d.soloMode().enabled());

        List<QueueModeEntry> ordered = d.enabledOrdered();
        assertEquals(3, ordered.size());
        assertEquals(QueueModeId.PUBLIC, ordered.get(0).mode());
        assertEquals(QueueModeId.PARTY, ordered.get(1).mode());
        assertEquals(QueueModeId.SOLO, ordered.get(2).mode());

        // No content is baked into the library: icons are authored in the consumer's /Server/ asset.
        assertEquals("", d.publicMode().iconItemId());
        assertEquals("", d.partyMode().iconItemId());
        assertEquals("", d.soloMode().iconItemId());
    }

    @Test
    void fromNullIsFallback() {
        QueueModeSet s = QueueModeSet.from(null);
        assertEquals(QueueModeSet.fallback().enabledOrdered().size(), s.enabledOrdered().size());
        assertTrue(s.soloMode().enabled());
        assertEquals("", s.soloMode().iconItemId());
    }

    @Test
    void authoringOnlyOverridesWhatItSets() {
        // Author ONLY "Solo disabled" + a custom Public icon; everything else stays default.
        InstancePresetAsset.QueueModes raw = new InstancePresetAsset.QueueModes();
        InstancePresetAsset.QueueMode solo = new InstancePresetAsset.QueueMode();
        solo.enabled = false;
        raw.soloMode = solo;
        InstancePresetAsset.QueueMode pub = new InstancePresetAsset.QueueMode();
        pub.iconItemId = "Custom_Icon";
        raw.publicMode = pub;

        QueueModeSet s = QueueModeSet.from(raw);

        // Solo gated off -> excluded from the cards.
        assertFalse(s.soloMode().enabled());
        List<QueueModeEntry> ordered = s.enabledOrdered();
        assertEquals(2, ordered.size());
        assertEquals(QueueModeId.PUBLIC, ordered.get(0).mode());
        assertEquals(QueueModeId.PARTY, ordered.get(1).mode());

        // Public icon overridden; Party untouched (neutral fallback, no glyph); both still enabled.
        assertEquals("Custom_Icon", s.publicMode().iconItemId());
        assertTrue(s.publicMode().enabled());
        assertEquals("", s.partyMode().iconItemId());
    }

    @Test
    void authoredOrderResortsEnabledCards() {
        // Flip Solo before Public via explicit Order values.
        InstancePresetAsset.QueueModes raw = new InstancePresetAsset.QueueModes();
        InstancePresetAsset.QueueMode solo = new InstancePresetAsset.QueueMode();
        solo.order = 0;
        raw.soloMode = solo;
        InstancePresetAsset.QueueMode pub = new InstancePresetAsset.QueueMode();
        pub.order = 9;
        raw.publicMode = pub;

        List<QueueModeEntry> ordered = QueueModeSet.from(raw).enabledOrdered();
        assertEquals(QueueModeId.SOLO, ordered.get(0).mode());
        assertEquals(QueueModeId.PUBLIC, ordered.get(ordered.size() - 1).mode());
    }

    @Test
    void forModeReturnsTheMatchingEntry() {
        QueueModeSet d = QueueModeSet.fallback();
        assertSame(d.partyMode(), d.forMode(QueueModeId.PARTY));
        assertSame(d.soloMode(), d.forMode(QueueModeId.SOLO));
    }
}
