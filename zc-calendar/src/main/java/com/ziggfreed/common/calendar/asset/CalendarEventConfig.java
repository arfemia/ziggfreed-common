package com.ziggfreed.common.calendar.asset;

import java.util.Map;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.util.SafeLog;

/**
 * The {@code defaults < pack < owner} fold of every {@link CalendarEventAsset}, plus the owner's one
 * switch over all of them ({@code "$Enabled"} in {@code mods/ziggfreedcommon/calendar.json}).
 */
public final class CalendarEventConfig extends AbstractKeyedAssetConfig<CalendarEventAsset> {

    private static final CalendarEventConfig INSTANCE = new CalendarEventConfig();

    private volatile boolean globalEnabled = true;

    private CalendarEventConfig() {
    }

    @Nonnull
    public static CalendarEventConfig getInstance() {
        return INSTANCE;
    }

    /** The owner's switch over every event at once; true unless the owner file says otherwise. */
    public boolean isGlobalEnabled() {
        return globalEnabled;
    }

    public void setGlobalEnabled(boolean on) {
        globalEnabled = on;
    }

    /**
     * One warning per problem per event, so an author learns why an event never runs, and one info line per
     * note, so an author learns what the file states that is not used.
     */
    public void reportProblems() {
        for (Map.Entry<String, CalendarEventAsset> entry : all().entrySet()) {
            for (String problem : entry.getValue().problems()) {
                SafeLog.warn("[calendar] the calendar event '" + entry.getKey() + "' " + sentence(problem));
            }
            for (String note : entry.getValue().notes()) {
                SafeLog.info("[calendar] the calendar event '" + entry.getKey() + "' " + sentence(note));
            }
        }
    }

    @Nonnull
    static String sentence(@Nonnull String problem) {
        return switch (problem) {
            case CalendarEventAsset.PROBLEM_ID_RESERVED ->
                    "has an id a calendar switch already uses (Calendar, Almanac, or one ending in _Live), so it"
                            + " never runs; rename its file";
            case CalendarEventAsset.PROBLEM_ID_UNSAVABLE ->
                    "has an id carrying '|' or '@', which a player's attendance record cannot save, so it never"
                            + " runs; rename its file";
            case CalendarEventAsset.PROBLEM_WINDOW_MISSING -> "has no Window, so it never runs";
            case CalendarEventAsset.PROBLEM_WINDOW_UNREADABLE ->
                    "has a Window whose days cannot be read (a Start or End that is not an MM-DD day, or a Rule "
                            + "missing its Month, Weekday or Nth), so it never runs";
            case CalendarEventAsset.PROBLEM_WINDOW_RUN_INVALID ->
                    "has a Window Rule whose runs could start in the year before or last more than 366 days (or "
                            + "a negative Before or After, or an Nth other than 1 to 5 or -1), so it never runs";
            case CalendarEventAsset.PROBLEM_YEARS_ENTRY_IGNORED ->
                    "has a Window Years entry that is not a four-digit year from its FirstYear on with MM-DD "
                            + "Start and End days, so that entry is not used";
            case CalendarEventAsset.NOTE_START_END_IGNORED ->
                    "has a Window Rule, so its Window Start and End are not used; to give it the same days "
                            + "every year, write Rule { Type: Fixed, Start, End }";
            case CalendarEventAsset.PROBLEM_FIRST_YEAR_MISSING -> "has no FirstYear, so it never runs";
            case CalendarEventAsset.PROBLEM_FIRST_YEAR_OUT_OF_RANGE ->
                    "has a FirstYear before " + CalendarEventAsset.MIN_FIRST_YEAR + " or after "
                            + CalendarEventAsset.MAX_FIRST_YEAR + ", so it never runs";
            case CalendarEventAsset.PROBLEM_CLOCK_UNKNOWN ->
                    "names a Clock this server does not know, so its days are counted in UTC";
            default -> "has a problem: " + problem;
        };
    }
}
