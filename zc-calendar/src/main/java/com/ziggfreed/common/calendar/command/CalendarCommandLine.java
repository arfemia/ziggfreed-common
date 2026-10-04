package com.ziggfreed.common.calendar.command;

/** What the family is CALLED and the argument it binds. A leaf: it imports nothing. */
public final class CalendarCommandLine {

    public static final String FAMILY = "zigcalendar";
    public static final String LIST = "list";
    public static final String STATUS = "status";
    public static final String FORCE = "force";
    public static final String RELOAD = "reload";

    /** A calendar event, by its file name. */
    public static final String ARG_EVENT = "event";

    private CalendarCommandLine() {
    }
}
