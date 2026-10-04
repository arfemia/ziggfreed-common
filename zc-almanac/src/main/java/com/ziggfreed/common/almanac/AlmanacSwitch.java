package com.ziggfreed.common.almanac;

import com.ziggfreed.common.factor.FeatureFlags;

/**
 * Whether the Almanac exists on this server. Off means ABSENT, not locked: nothing is counted, the
 * page cannot be opened, the command says so, the destination declines, and content gated on the
 * feature {@code ziggfreedcommon:feature} Param {@code Almanac} vanishes rather than showing locked.
 *
 * <p>The state is the owner file's {@code $Enabled} ({@code AlmanacOwnerLayers}), on until it says
 * otherwise. The feature reads it fresh on every ask, so a re-read is in force at once.
 */
public final class AlmanacSwitch {

    /** The feature namespace: the library's own. */
    public static final String NAMESPACE = "ziggfreedcommon";

    /**
     * The feature id content gates on. It shares the {@value #NAMESPACE} feature namespace with the
     * calendar's own switches ({@code Calendar}, each event's id and its {@code <Id>_Live}), where
     * declaring an id again replaces its supplier, so the calendar refuses {@code Almanac} as an event
     * id: no calendar event file can take this switch over.
     */
    public static final String FEATURE = "Almanac";

    private static volatile boolean on = true;

    private AlmanacSwitch() {
    }

    /** Is the Almanac switched on right now? */
    public static boolean isOn() {
        return on;
    }

    /** Set by the owner file's reader. */
    public static void set(boolean value) {
        on = value;
    }

    /** Declare the feature. Once, at setup, before the first progression publish. */
    public static void registerFeature() {
        FeatureFlags.register(NAMESPACE, FEATURE, NAMESPACE, AlmanacSwitch::isOn);
    }

    /** Back to on, for a test starting from nothing. */
    public static void resetForTests() {
        on = true;
    }
}
