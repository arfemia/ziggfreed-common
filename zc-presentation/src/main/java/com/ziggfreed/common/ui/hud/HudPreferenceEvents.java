package com.ziggfreed.common.ui.hud;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.event.NativeEventSeam;

/**
 * Fires the HUD preference family's native event on the shared engine event bus, under the
 * library-wide contract ({@link NativeEventSeam}): built only when somebody is listening,
 * dispatched synchronously on the calling (world) thread, and never able to take the write that
 * caused it down with it.
 *
 * <p>The one event ({@link ZigHudPreferenceChangedEvent}) is fired by {@link HudPreferences} alone,
 * and only for a REAL change. A third party listens on the bus with no compile-time dependency
 * beyond the event class; a host running without a bus (a harness, a unit JVM) redirects the
 * family through {@link #publishTo}.
 */
public final class HudPreferenceEvents {

    private static final NativeEventSeam SEAM = new NativeEventSeam("[hud]");

    private HudPreferenceEvents() {
    }

    /**
     * Route every fire through {@code publisher} instead of the engine bus; null restores the bus.
     * For a host outside a Hytale server and for a test observing what the facade fires.
     */
    public static void publishTo(@Nullable NativeEventSeam.Publisher publisher) {
        SEAM.publishTo(publisher);
    }

    /** {@code playerRef}'s HUD preferences really changed, about {@code panelId} or about every panel. */
    static void fireChanged(@Nonnull UUID playerId, @Nonnull PlayerRef playerRef, @Nullable String panelId) {
        SEAM.fire("ZigHudPreferenceChanged", ZigHudPreferenceChangedEvent.class,
                () -> new ZigHudPreferenceChangedEvent(playerId, playerRef, panelId));
    }
}
