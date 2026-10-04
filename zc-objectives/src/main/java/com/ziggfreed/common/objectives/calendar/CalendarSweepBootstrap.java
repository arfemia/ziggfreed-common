package com.ziggfreed.common.objectives.calendar;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.calendar.event.CalendarEventEndedEvent;
import com.ziggfreed.common.calendar.event.CalendarEventStartedEvent;
import com.ziggfreed.common.util.SafeLog;

/**
 * Hangs the placement sweep on the calendar's two native events. Registration only; every decision lives
 * in {@link CalendarPlacementSweep}.
 */
public final class CalendarSweepBootstrap {

    private CalendarSweepBootstrap() {
    }

    /**
     * Listen for every calendar start and end on the shared bus. Call from plugin {@code setup()}: the
     * boot's resumed start fires on {@code BootEvent}, after every setup has returned.
     */
    public static void registerPlacementSweeps(@Nonnull PluginBase plugin) {
        try {
            plugin.getEventRegistry().registerGlobal(CalendarEventStartedEvent.class,
                    CalendarPlacementSweep::onStarted);
            plugin.getEventRegistry().registerGlobal(CalendarEventEndedEvent.class,
                    CalendarPlacementSweep::onEnded);
        } catch (Throwable t) {
            SafeLog.warn("[placement] could not hang the placement sweep on the calendar's events", t);
        }
    }
}
