package com.ziggfreed.common.encounter.event;

import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.event.NativeEventSeam;

/**
 * Where the encounter family's six native events go: the shared engine event bus, under the
 * library-wide contract ({@link NativeEventSeam}). Each event is built only when somebody is
 * listening, dispatched synchronously on the calling (world) thread, and can never take the run
 * that fired it down with it.
 *
 * <p>Nothing outside this module fires these; the runtime does, at the moments their javadoc names.
 * A third party listens on the bus with no compile-time dependency beyond the event class; a host
 * running without a bus (a harness, a unit JVM) redirects the family through {@link #publishTo}.
 */
public final class Encounters {

    /** The log prefix every line this module writes carries. */
    public static final String LOG_PREFIX = "[encounter]";

    private static final NativeEventSeam SEAM = new NativeEventSeam(LOG_PREFIX);

    private Encounters() {
    }

    /**
     * Route every fire through {@code publisher} instead of the engine bus; null restores the bus.
     * For a host outside a Hytale server and for a test observing what the runtime fires. Not for a
     * mod: on a live server the bus is the one place a listener looks.
     */
    public static void publishTo(@Nullable NativeEventSeam.Publisher publisher) {
        SEAM.publishTo(publisher);
    }

    public static void fireEngaged(@Nonnull Supplier<EncounterEngagedEvent> build) {
        SEAM.fire("EncounterEngaged", EncounterEngagedEvent.class, build);
    }

    public static void firePhaseChanged(@Nonnull Supplier<EncounterPhaseChangedEvent> build) {
        SEAM.fire("EncounterPhaseChanged", EncounterPhaseChangedEvent.class, build);
    }

    public static void fireDefeated(@Nonnull Supplier<EncounterDefeatedEvent> build) {
        SEAM.fire("EncounterDefeated", EncounterDefeatedEvent.class, build);
    }

    public static void fireWiped(@Nonnull Supplier<EncounterWipedEvent> build) {
        SEAM.fire("EncounterWiped", EncounterWipedEvent.class, build);
    }

    public static void fireReset(@Nonnull Supplier<EncounterResetEvent> build) {
        SEAM.fire("EncounterReset", EncounterResetEvent.class, build);
    }

    public static void fireSignal(@Nonnull Supplier<EncounterSignalEvent> build) {
        SEAM.fire("EncounterSignal", EncounterSignalEvent.class, build);
    }
}
