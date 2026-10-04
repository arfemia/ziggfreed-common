package com.ziggfreed.common.calendar;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.event.events.BootEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.ziggfreed.common.calendar.attendance.CalendarAttendance;
import com.ziggfreed.common.calendar.attendance.CalendarAttendanceComponent;
import com.ziggfreed.common.calendar.command.ZigCalendarCommand;
import com.ziggfreed.common.calendar.spawn.CalendarSpawns;
import com.ziggfreed.common.occurrence.Occurrences;
import com.ziggfreed.common.util.SafeLog;

/**
 * The calendar module's registration phase, called as one line from the wiring root's {@code setup()}: the
 * feature namespace, the two readings and the occurrence slot (before anything folds content), the
 * attendance record and its hooks, the tick's listeners and its start, and /zigcalendar. Registration only;
 * every decision lives in the module behind it.
 *
 * <p>The tick starts on {@code BootEvent}, never on a world being added: worlds are added inside the
 * universe's own start, possibly before the spawning plugin sets up the rules already in its store, and a
 * rule the calendar wrote then would be set up twice (the engine refuses the second with a SEVERE line).
 */
public final class CalendarBootstrap {

    private CalendarBootstrap() {
    }

    /** Install the whole module. */
    public static void install(@Nonnull JavaPlugin plugin) {
        declareVocabulary();
        registerAttendance(plugin);
        registerTick(plugin);
        registerCommand(plugin);
    }

    /** Stop the minute tick. Called from the library's {@code shutdown()}. */
    public static void shutdown() {
        try {
            CalendarRuntime.shutdown();
        } catch (Throwable t) {
            SafeLog.warn("[calendar] could not stop the calendar tick", t);
        }
    }

    private static void declareVocabulary() {
        try {
            CalendarFeatures.declare(CalendarRuntime.service());
            CalendarFactors.contribute(CalendarRuntime.service(), CalendarRuntime::now);
            Occurrences.fill(CalendarRuntime.service());
        } catch (Throwable t) {
            SafeLog.warn("[calendar] could not declare the calendar's features, readings and occurrences", t);
        }
    }

    private static void registerAttendance(@Nonnull JavaPlugin plugin) {
        try {
            CalendarAttendanceComponent.register(plugin.getEntityStoreRegistry());
            CalendarAttendanceComponent.install(plugin);
            plugin.getEventRegistry().registerGlobal(PlayerReadyEvent.class, CalendarAttendance::onPlayerReady);
            CalendarRuntime.ticker().listen(CalendarAttendance::onTick);
            CalendarRuntime.ticker().listen(CalendarHerald::onTick);
        } catch (Throwable t) {
            SafeLog.warn("[calendar] could not register calendar attendance", t);
        }
    }

    private static void registerTick(@Nonnull JavaPlugin plugin) {
        try {
            CalendarRuntime.ticker().listen(CalendarSpawns::onTick);
            plugin.getEventRegistry().register(BootEvent.class, event -> CalendarRuntime.ticker().start());
        } catch (Throwable t) {
            SafeLog.warn("[calendar] could not hang the calendar tick", t);
        }
    }

    private static void registerCommand(@Nonnull JavaPlugin plugin) {
        try {
            plugin.getCommandRegistry().registerCommand(new ZigCalendarCommand());
        } catch (Throwable t) {
            SafeLog.warn("[calendar] could not register /zigcalendar", t);
        }
    }
}
