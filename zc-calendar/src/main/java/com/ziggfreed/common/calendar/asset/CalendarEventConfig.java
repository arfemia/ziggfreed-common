package com.ziggfreed.common.calendar.asset;

import java.util.Map;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.occurrence.Occurrence;
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
                    "has a Window whose days cannot be read (a Start or End that is not an MM-DD day, a Fixed Rule "
                            + "whose Runs is empty, holds such a span or holds more than " + Occurrence.MAX_NUMBER
                            + " spans, or a Rule missing a leaf it needs, or naming "
                            + "a weekday, a month, an At time, a Length or an Anchor it cannot read, or an Every past "
                            + "1 with no Anchor), so it never runs";
            case CalendarEventAsset.PROBLEM_WINDOW_RUN_INVALID ->
                    "has a Window whose runs could start in the year before or run into the next one (a negative "
                            + "Before or After, a span too long to stay clear of next year's run, an Nth other than 1 "
                            + "to 5 or -1, a Day other than 1 to 31, Days or a Length past 28 days a month or 7 a week "
                            + "for each Every, no Months, UntilNext with Months or too large an Every, or February "
                            + "29th through February 28th), so it never runs";
            case CalendarEventAsset.PROBLEM_YEARS_ENTRY_IGNORED ->
                    "has a Window Years entry that is not a four-digit year from its FirstYear on with MM-DD "
                            + "days (Start and End, or each of its Runs, at most " + Occurrence.MAX_NUMBER
                            + " of them; February 29th through February 28th is refused, since it meets its own next "
                            + "run), so that entry is not used";
            case CalendarEventAsset.PROBLEM_RUN_SET_ASIDE ->
                    "has a run that meets a run before it (in its list, or the year before's), so that run is set "
                            + "aside: runs of one event never overlap, the one written first is kept, and the "
                            + "content audit names each run set aside";
            case CalendarEventAsset.PROBLEM_SKIP_UNKNOWN_RUN ->
                    "has a Window Years entry whose Skip names a run number its year does not have (a run in a "
                            + "list is its place, a Monthly run its month, a Weekly run its calendar week), so that "
                            + "number skips nothing; the content audit names each";
            case CalendarEventAsset.NOTE_START_END_IGNORED ->
                    "has a Window Rule, so its Window Start and End are not used; to give it the same days "
                            + "every year, write Rule { Type: Fixed, Start, End }";
            case CalendarEventAsset.NOTE_START_END_BESIDE_RUNS ->
                    "has Start and End beside Runs in a Fixed Rule or a Years entry; Runs wins, so that Start "
                            + "and End are not used";
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
