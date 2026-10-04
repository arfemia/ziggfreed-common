package com.ziggfreed.common.calendar.command;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/**
 * {@code /zigcalendar} - the admin surface over this server's calendar events.
 *
 * <pre>
 * /zigcalendar list                       every calendar event and whether it runs
 * /zigcalendar status &lt;event&gt;            one event: its dates, first year, runs so far
 * /zigcalendar force on|off|clear &lt;event&gt; run it now, stop it now, or hand it back to its dates
 * /zigcalendar reload                     read mods/ziggfreedcommon/calendar.json again
 * </pre>
 *
 * <p>No permission check is written here: the engine derives one node per command from the plugin and the
 * command names and refuses the call before a body runs. A force lives in memory only.
 */
public final class ZigCalendarCommand extends AbstractCommandCollection {

    public ZigCalendarCommand() {
        super(CalendarCommandLine.FAMILY, CalendarAdminMessages.desc("family"));
        addSubCommand(new CalendarListCommand());
        addSubCommand(new CalendarStatusCommand());
        addSubCommand(new ForceGroup());
        addSubCommand(new CalendarReloadCommand());
    }

    /** {@code /zigcalendar force ...}: one verb each for on, off and clear. */
    private static final class ForceGroup extends AbstractCommandCollection {

        ForceGroup() {
            super(CalendarCommandLine.FORCE, CalendarAdminMessages.desc(CalendarCommandLine.FORCE));
            addSubCommand(new CalendarForceCommand(CalendarForceCommand.Move.ON));
            addSubCommand(new CalendarForceCommand(CalendarForceCommand.Move.OFF));
            addSubCommand(new CalendarForceCommand(CalendarForceCommand.Move.CLEAR));
        }
    }
}
