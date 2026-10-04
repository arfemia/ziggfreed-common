package com.ziggfreed.common.almanac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.factor.FeatureFlags;

/** The kill switch, as content anywhere reads it: the feature {@code ziggfreedcommon:feature} Param {@code Almanac}. */
class AlmanacSwitchTest {

    @AfterEach
    void reset() {
        FeatureFlags.reset();
        AlmanacSwitch.resetForTests();
    }

    @Test
    void theFeatureReadsTheSwitchFresh() {
        AlmanacSwitch.registerFeature();
        assertTrue(FeatureFlags.isKnown(AlmanacSwitch.NAMESPACE, AlmanacSwitch.FEATURE));
        assertEquals(1.0, FeatureFlags.read(AlmanacSwitch.NAMESPACE, "Almanac"));

        AlmanacSwitch.set(false);

        assertEquals(0.0, FeatureFlags.read(AlmanacSwitch.NAMESPACE, "almanac"),
                "read fresh on every ask, and the feature id matches without regard to case");
    }
}
