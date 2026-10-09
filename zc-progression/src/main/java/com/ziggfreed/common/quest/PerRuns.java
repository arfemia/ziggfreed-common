package com.ziggfreed.common.quest;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.occurrence.Occurrence;
import com.ziggfreed.common.occurrence.OccurrenceSource;
import com.ziggfreed.common.quest.QuestProgressStore.CompletionRecord;

/**
 * The once-a-run arithmetic behind {@link Quest.Repeat.PerRun}: which run of its calendar event a finish
 * counts for, which runs a completion record has spent, and when a spent quest comes back. It reads the calendar
 * through zc-core's occurrence seam, so this module never sees the calendar.
 *
 * <p><b>Runs are keyed (event, year, number)</b> ({@link RunKey}): the year a run starts in and the number
 * its event's dates name it by, never whether its days contain now. An event may come round several times
 * a year, and each run is its own once. A run forced on keeps its number, a run whose days an owner moved
 * mid-run stays the run it was, and every day of a run crossing the new year counts for the year it began in.
 *
 * <p><b>Within the record's year a run is judged by what it is, never by where its days now fall.</b> A record
 * keeps the run it counts now with its tally ({@code runYear}, {@code runNumber}, {@code runCount}) and the other
 * runs of that year it has spent ({@code spentRuns}, a set of numbers from 1 to {@link Occurrence#MAX_NUMBER}).
 * An owner may move any run anywhere in its year: a run the player spent stays spent, and a run they never played
 * stays on offer, whichever of the two now comes first.
 *
 * <p><b>Across years, by year.</b> A finish counts for the run going on, else the next one, and never for a year
 * earlier than the record's: a record counted for a later year reads every run of an earlier year as spent, so a
 * run forced on after it cannot pay the quest again. A finish in a run of the record's year the record already
 * spent (a close-out from outside play), or with no run going on or due, joins the run the record counts.
 *
 * <p>So a run is spent when its year is earlier than the record's, or it is one of the record's year's spent
 * runs, or it is the run the record counts and that run holds {@code Times} finishes ({@link #spentIn}). A record
 * that moves on to another run of its year spends the run it leaves, whatever its tally: a run left with a finish
 * to spare pays nothing more when an owner moves it later.
 *
 * <p><b>The wait names the first run after now that is not spent</b>, walking the calendar's runs in time order
 * ({@link OccurrenceSource#after}) past the spent ones. Time order matters only there, and in whether the run a
 * quest that does not carry over was taken in is over ({@link #comesAfter}).
 *
 * <p><b>An old record</b>, saved before the run tally existed, carries no run year: it belongs to run 1 of
 * the year of the run nearest its last finish by the event's dates as they stand now ({@code nearestRunYear},
 * the one place that places it), and holds one finish there. Every such record was saved for an event that
 * came round once a year. A record with no last finish belongs to no run.
 */
public final class PerRuns {

    /** The calendar dates no first year after it: a stored finish later than this is a corrupt value. */
    private static final int LAST_FINISH_YEAR = 9999;

    /**
     * The most runs the wait walks past. Every run of a year before the record's is spent, and so are at most all of
     * the record's own year's runs, a year having at most {@link Occurrence#MAX_NUMBER}. A finish counts for the run
     * going on or the next, so the runs still to come before the record's year are the rest of one year unless an
     * owner dates runs into a year between them; three years of runs is past every such walk. Were it ever to run
     * out, the quest would wait ("never") rather than be offered in a spent run.
     */
    private static final int WAIT_WALK = 3 * Occurrence.MAX_NUMBER;

    private PerRuns() {
    }

    /**
     * A run as a record keeps it: the year it starts in and the number its event's dates name it by within
     * that year. An identity only, deliberately not {@link Comparable}: a number is no place in time, so a
     * run is put in order against a key by {@link PerRuns#comesAfter}, never by comparing keys.
     */
    public record RunKey(int year, int number) {

        /** The key of {@code run}. */
        @Nonnull
        public static RunKey of(@Nonnull Occurrence run) {
            return new RunKey(run.year(), run.number());
        }

        /** Is {@code run} this run, wherever its days now fall? */
        public boolean is(@Nullable Occurrence run) {
            return run != null && run.year() == year && run.number() == number;
        }
    }

    /**
     * Where a finish is recorded: the run it counts for, how many finishes that run held before it, and the other
     * runs of that run's year the record has spent once it is recorded.
     *
     * @param run    the run the finish counts for
     * @param before the finishes the record already held for that run; the finish being recorded is not in it
     * @param spent  the record's spent runs of {@code run}'s year, as {@link CompletionRecord#spentRuns()} keeps them
     */
    public record RunTally(@Nonnull RunKey run, int before, long spent) {
    }

    /** The run a finish at {@code nowMs} counts for: the one going on, else the next, else none. */
    @Nullable
    public static Occurrence runFor(@Nonnull Quest.Repeat.PerRun perRun, long nowMs,
            @Nonnull OccurrenceSource occurrences) {
        Occurrence live = occurrences.live(perRun.event(), nowMs);
        return live != null ? live : occurrences.next(perRun.event(), nowMs);
    }

    /**
     * The run {@code record} counts for: its own, else (an old record) run 1 of the year of the run nearest its
     * last finish ({@code nearestRunYear}); null when it counts for none. No clock moves the answer.
     */
    @Nullable
    public static RunKey runOf(@Nonnull CompletionRecord record, @Nonnull Quest.Repeat.PerRun perRun,
            @Nonnull OccurrenceSource occurrences) {
        if (record.runYear() != null) {
            return new RunKey(record.runYear(), record.runNumber());
        }
        Integer year = nearestRunYear(perRun, record.lastCompletionMs(), occurrences);
        return year == null ? null : new RunKey(year, 1);
    }

    /**
     * Does {@code run} come after the run {@code key} names, in time? A later year always does. Within the
     * key's year, {@code run} comes after it when it starts no earlier than the run the calendar answers as
     * the one after it ({@link OccurrenceSource#after}): that answer follows the key's run from its own start,
     * kept or set aside, or from where its number would fall when the year lacks it. False for the key's own
     * run, and for every run of its year when the calendar answers no run after it that year (none is left, or
     * a source that knows no dates).
     *
     * <p>Asked only where time genuinely matters: whether the run a quest that does not carry over was taken in is
     * over ({@code QuestEngine.dropIfRunEnded}). Whether a run is spent is never asked this way ({@link #spentIn}).
     */
    public static boolean comesAfter(@Nonnull Occurrence run, @Nonnull RunKey key,
            @Nonnull Quest.Repeat.PerRun perRun, @Nonnull OccurrenceSource occurrences) {
        if (run.year() != key.year()) {
            return run.year() > key.year();
        }
        if (key.is(run)) {
            return false;
        }
        Occurrence after = occurrences.after(perRun.event(), key.year(), key.number());
        return after != null && after.year() == key.year() && run.startMs() >= after.startMs();
    }

    /**
     * The run an old record (one saved before the run tally, so with no run year) belongs to: the run of
     * {@code perRun}'s event nearest its last finish at {@code finishMs}, by the event's dates as they
     * stand now. A run whose days hold the finish wins outright; otherwise the run least far from it wins
     * (the time until it starts, or since it ended), among the runs of the finish's own year on the
     * event's clock and the years either side, and on a tie the earlier run. An old record holds one
     * finish, in run 1 of this run's year ({@link #runOf}).
     *
     * <p>Every such record was finished inside a run of a once-a-year event, since a once-a-run quest is
     * on offer only while its run is going on, so this is its own run after any re-dating of less than
     * half a year. It needs nothing saved, and it holds whenever the player next logs in, even when the
     * owner moved the dates before that. Null when the record holds no finish (or one dated past the
     * calendar's years), or no run of those years exists (the event absent, or not yet at its first year).
     */
    @Nullable
    static Integer nearestRunYear(@Nonnull Quest.Repeat.PerRun perRun, long finishMs,
            @Nonnull OccurrenceSource occurrences) {
        if (finishMs <= 0L) {
            return null;
        }
        ZoneId zone = occurrences.zone(perRun.event());
        int year = Instant.ofEpochMilli(finishMs).atZone(zone).getYear();
        if (year > LAST_FINISH_YEAR) {
            return null;
        }
        // A run's year is the year it starts in, so every run of the year after has begun by that year's end.
        long horizon = LocalDate.of(year + 2, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli() - 1L;
        Occurrence nearest = null;
        long nearestGap = Long.MAX_VALUE;
        for (Occurrence run : occurrences.history(perRun.event(), horizon)) {
            if (run.year() < year - 1 || run.year() > year + 1) {
                continue;
            }
            if (run.contains(finishMs)) {
                return run.year();
            }
            long gap = finishMs < run.startMs() ? run.startMs() - finishMs : finishMs - run.endMs();
            if (nearest == null || gap < nearestGap || (gap == nearestGap && run.year() < nearest.year())) {
                nearest = run;
                nearestGap = gap;
            }
        }
        return nearest == null ? null : nearest.year();
    }

    /**
     * Where a finish at {@code nowMs} is recorded: {@link #runFor}, with the finishes that run held before this
     * one and the runs the record has spent in its year after it. A run of a later year than the record's starts
     * afresh, with nothing spent; another run of the record's year the record has not spent becomes the run it
     * counts, and the run it leaves joins the spent ones. The record's own run, a run of its year it already spent,
     * a run of an earlier year, or no run going on or due: the record's run stands, and the finish joins it. Null
     * when no run is going on, due or counted.
     */
    @Nullable
    public static RunTally runToRecord(@Nonnull CompletionRecord prior, @Nonnull Quest.Repeat.PerRun perRun,
            long nowMs, @Nonnull OccurrenceSource occurrences) {
        Occurrence run = runFor(perRun, nowMs, occurrences);
        RunKey counted = runOf(prior, perRun, occurrences);
        if (counted == null) {
            return run == null ? null : new RunTally(RunKey.of(run), 0, 0L);
        }
        if (run != null && run.year() > counted.year()) {
            return new RunTally(RunKey.of(run), 0, 0L);
        }
        if (run != null && run.year() == counted.year() && !counted.is(run) && !prior.spentRun(run.number())) {
            return new RunTally(RunKey.of(run), 0, prior.spentRuns() | CompletionRecord.runBit(counted.number()));
        }
        return new RunTally(counted, heldIn(prior), prior.spentRuns());
    }

    /**
     * How many finishes {@code record} holds for {@code run}: every finish {@code run} allows when it is spent (a
     * run of an earlier year than the record's, or one of the record's year's spent runs), the record's own tally
     * when it is the run the record counts, and none for any other run.
     */
    public static int spentIn(@Nonnull CompletionRecord record, @Nonnull Occurrence run,
            @Nonnull Quest.Repeat.PerRun perRun, @Nonnull OccurrenceSource occurrences) {
        return spentIn(record, runOf(record, perRun, occurrences), run, perRun);
    }

    /** {@link #spentIn(CompletionRecord, Occurrence, Quest.Repeat.PerRun, OccurrenceSource)} with the record's run read once. */
    private static int spentIn(@Nonnull CompletionRecord record, @Nullable RunKey counted, @Nonnull Occurrence run,
            @Nonnull Quest.Repeat.PerRun perRun) {
        if (counted == null || run.year() > counted.year()) {
            return 0;
        }
        if (run.year() < counted.year()) {
            return perRun.times();
        }
        if (counted.is(run)) {
            return heldIn(record);
        }
        return record.spentRun(run.number()) ? perRun.times() : 0;
    }

    /** The finishes {@code record} holds for the run it counts for: its tally, or the one finish of an old record. */
    private static int heldIn(@Nonnull CompletionRecord record) {
        return record.runYear() != null ? record.runCount() : 1;
    }

    /**
     * The once-a-run half of the repeat rule: null while a run is going on and the player has not spent it;
     * otherwise the refusal, offerable again when the first run after now the player has not spent starts, found
     * by walking the runs in time order past the spent ones (never, when none is left).
     */
    @Nullable
    static QuestLifecycle.RepeatCheck refusal(@Nonnull Quest.Repeat.PerRun perRun,
            @Nonnull CompletionRecord completions, long nowMs, @Nonnull OccurrenceSource occurrences) {
        RunKey counted = runOf(completions, perRun, occurrences);
        Occurrence live = occurrences.live(perRun.event(), nowMs);
        if (live != null && spentIn(completions, counted, live, perRun) < perRun.times()) {
            return null;
        }
        Occurrence back = occurrences.next(perRun.event(), nowMs);
        int walked = 0;
        while (back != null && spentIn(completions, counted, back, perRun) >= perRun.times()) {
            back = ++walked > WAIT_WALK ? null : occurrences.after(perRun.event(), back.year(), back.number());
        }
        return new QuestLifecycle.RepeatCheck(false, QuestGates.REASON_RUN_SPENT,
                back == null ? Long.MAX_VALUE : back.startMs());
    }
}
