package com.ziggfreed.common.instance.leaderboard.statboard;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.occurrence.Occurrence;
import com.ziggfreed.common.occurrence.OccurrenceSource;

/**
 * One stat period as the board runs it, resolved from its file: which window a stat moving now lands
 * in, which window its tab reads, and which buckets are its old windows.
 *
 * <p>A bucket is named by the period: {@code all_time} for the one that never ends,
 * {@code <id>@<window>} for a week or a month ({@code weekly@2026-W42}, {@code monthly@2026-10}),
 * {@code <id>@<event>:<run>} for a season's run ({@code season@hallows_eve:2026}, {@code 2026#09} for a
 * year's run 9) and {@code range@<id>} for a range. A new window's key is the reset: nothing is cleared,
 * the board simply starts writing and reading a bucket nobody has written yet, and keeps the last
 * {@link #keep} of them (of a season, per event).
 *
 * @param id       the period's file id, lower-cased
 * @param nameKey  the tab's name, a translation key; null shows the id
 * @param order    where the tab sits, lowest first
 * @param zone     the clock a week, a month or a range is read on
 * @param startsOn the day a week starts on
 * @param keep     how many windows of a week or a month the board keeps, the running one included, and how
 *                 many runs of each event a season keeps
 * @param from     a range's first moment, on {@code zone}'s clock; null for open
 * @param to       the moment a range ends (left out), on {@code zone}'s clock; null for open
 * @param event    the calendar event a season follows, lower-cased; null for every event that is live
 */
public record StatPeriod(@Nonnull String id, @Nonnull PeriodKind kind, @Nullable String nameKey, int order,
                         @Nonnull ZoneId zone, @Nonnull DayOfWeek startsOn, int keep,
                         @Nullable LocalDateTime from, @Nullable LocalDateTime to, @Nullable String event) {

    /** The bucket of the period that never ends. */
    public static final String ALL_TIME = "all_time";

    /** What joins a period to its window in a bucket key. */
    public static final String AT = "@";

    /** What a season's {@code Event} says to follow every event that is live. */
    public static final String EVERY_EVENT = "*";

    /** By event, then newest run first: a season's tabs. */
    private static final Comparator<PeriodWindows.Run> TAB_ORDER = Comparator.comparing(PeriodWindows.Run::eventId)
            .thenComparing(Comparator.comparingInt(PeriodWindows.Run::year).reversed())
            .thenComparing(Comparator.comparingInt(PeriodWindows.Run::number).reversed());

    /**
     * One bucket a stat moving now lands in, and what every window of its kind starts with, so the board can
     * keep the newest {@link #keep} of them; {@code prefix} is null for a bucket never dropped.
     */
    public record Window(@Nonnull String key, @Nullable String prefix) {
    }

    /**
     * The bucket a stat moving at {@code now} lands in, or null while this period is not running (a range
     * outside its dates). Null for a season too, whose buckets come from {@link #windows}, which asks the
     * calendar.
     */
    @Nullable
    public String writeKey(@Nonnull Instant now) {
        return switch (kind) {
            case ALL_TIME -> ALL_TIME;
            case WEEKLY -> id + AT + PeriodWindows.weekKey(now, zone, startsOn);
            case MONTHLY -> id + AT + PeriodWindows.monthKey(now, zone);
            case RANGE -> PeriodWindows.within(now, zone, from, to) ? rangeKey() : null;
            case SEASON_RUN -> null;
        };
    }

    /**
     * The bucket this period's tab reads at {@code now}: the running window, or a range from the moment
     * it starts, still readable after it ends; null for a range that has not started, which shows no tab,
     * and for a season, whose tabs come from {@link #readKeys}.
     */
    @Nullable
    public String readKey(@Nonnull Instant now) {
        if (kind == PeriodKind.RANGE) {
            return PeriodWindows.started(now, zone, from) ? rangeKey() : null;
        }
        return writeKey(now);
    }

    /**
     * What every window of a week or a month starts with, so the old ones can be dropped; null for a period
     * of one window, and for a season, whose runs are dropped per event ({@link #runPrefix}).
     */
    @Nullable
    public String windowPrefix() {
        return kind == PeriodKind.WEEKLY || kind == PeriodKind.MONTHLY ? id + AT : null;
    }

    /**
     * Every bucket a stat moving at {@code now} lands in under this period: none, one, or for a season one per
     * live run of the events it follows, out of {@code live} (every event running at {@code now}, by id, as
     * {@link OccurrenceSource#liveAll} answers; a write asks {@code Occurrences.liveAllCached}).
     */
    @Nonnull
    public List<Window> windows(@Nonnull Instant now, @Nonnull Map<String, Occurrence> live) {
        if (kind == PeriodKind.SEASON_RUN) {
            List<Window> out = new ArrayList<>();
            for (Occurrence run : liveRuns(live)) {
                out.add(new Window(runKey(run.eventId(), run.year(), run.number()), runPrefix(run.eventId())));
            }
            return out;
        }
        String key = writeKey(now);
        return key == null ? List.of() : List.of(new Window(key, windowPrefix()));
    }

    /**
     * The buckets this period's tabs read at {@code now}, in tab order: the one {@link #readKey} names, or for
     * a season each live run of the events it follows and each run of such an event the board still holds
     * ({@code held}, its bucket keys), so a run stays readable after it ends; by event, newest run first. A run
     * of an event this server no longer loads ({@code runs} knows no year for it) shows no tab.
     */
    @Nonnull
    public List<String> readKeys(@Nonnull Instant now, @Nonnull OccurrenceSource runs,
                                 @Nonnull Collection<String> held) {
        if (kind != PeriodKind.SEASON_RUN) {
            String key = readKey(now);
            return key == null ? List.of() : List.of(key);
        }
        long nowMs = now.toEpochMilli();
        Map<String, PeriodWindows.Run> shown = new LinkedHashMap<>();
        for (Occurrence run : liveRuns(runs.liveAll(nowMs))) {
            shown.put(runKey(run.eventId(), run.year(), run.number()),
                    new PeriodWindows.Run(run.eventId(), run.year(), run.number()));
        }
        for (String bucket : held) {
            PeriodWindows.Run run = runOf(bucket);
            if (run != null && !shown.containsKey(bucket) && follows(run.eventId())
                    && runs.currentYear(run.eventId(), nowMs) != null) {
                shown.put(bucket, run);
            }
        }
        List<Map.Entry<String, PeriodWindows.Run>> ordered = new ArrayList<>(shown.entrySet());
        ordered.sort(Map.Entry.comparingByValue(TAB_ORDER));
        List<String> out = new ArrayList<>(ordered.size());
        for (Map.Entry<String, PeriodWindows.Run> e : ordered) {
            out.add(e.getKey());
        }
        return out;
    }

    /** A season's bucket for run {@code number} of {@code year} of {@code eventId}: {@code season@hallows_eve:2026}. */
    @Nonnull
    public String runKey(@Nonnull String eventId, int year, int number) {
        return id + AT + PeriodWindows.runWindow(eventId, year, number);
    }

    /** What every run of {@code eventId} under this season starts with, so its old runs can be dropped. */
    @Nonnull
    public String runPrefix(@Nonnull String eventId) {
        return id + AT + PeriodWindows.runWindowPrefix(eventId);
    }

    /** The run a season's {@code bucket} holds, or null for a bucket of another period or none this season writes. */
    @Nullable
    public PeriodWindows.Run runOf(@Nonnull String bucket) {
        if (kind != PeriodKind.SEASON_RUN || !bucket.startsWith(id + AT)) {
            return null;
        }
        PeriodWindows.Run run = PeriodWindows.parseRunWindow(bucket.substring(id.length() + AT.length()));
        return run != null && PeriodWindows.usableEvent(run.eventId()) ? run : null;
    }

    /** Does this season follow {@code eventId}: every event, or the one it names? */
    public boolean follows(@Nonnull String eventId) {
        return event == null || event.equals(eventId);
    }

    /** The runs of {@code live} (every running event's, by id) this season follows: its event's, or all of them. */
    @Nonnull
    private List<Occurrence> liveRuns(@Nonnull Map<String, Occurrence> live) {
        List<Occurrence> out = new ArrayList<>();
        for (Occurrence run : live.values()) {
            if (run != null && PeriodWindows.usableEvent(run.eventId()) && follows(run.eventId())) {
                out.add(run);
            }
        }
        return out;
    }

    @Nonnull
    private String rangeKey() {
        return "range" + AT + id;
    }
}
