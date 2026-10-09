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
 * counts for, how many finishes a completion record holds for one run, and when a spent quest comes back.
 * It reads the calendar through zc-core's occurrence seam, so this module never sees the calendar.
 *
 * <p><b>Runs are keyed (event, year, number)</b> ({@link RunKey}): the year a run starts in and the number
 * its event's dates name it by, never whether its days contain now. An event may come round several times
 * a year, and each run is its own once. A run forced on keeps its number, a run whose days an owner moved
 * mid-run stays the run it was, and every day of a run crossing the new year counts for the year it began in.
 *
 * <p><b>Runs come in time order, never number order.</b> A number names a run within its year and need not
 * follow its days (a monthly run is its month, a weekly run its calendar week, a list's span its place in
 * the list wherever its days move), so the identity is (year, number) and the order is (year, start). A
 * record keeps only its run's key, so a run is weighed against it through the calendar's own answer to
 * "the run after that one" ({@link OccurrenceSource#after}, {@link #comesAfter}): the same answer the wait
 * below names, so a run the wait names is never one the record still reads as spent.
 *
 * <p><b>Runs only move forward.</b> A finish counts for the run going on, else the next one, and never for a
 * run earlier than the one its record already counts for; a record counted for a later run reads as spent
 * for every earlier one, so a run forced on after it cannot pay the quest again. A quest counts no progress
 * while it is not on offer, so a player only ever finishes it inside a run; only a close-out from outside
 * play lands between runs.
 *
 * <p><b>The wait skips a spent run.</b> When the next run is one the record already spends (an owner moved a
 * spent run to start later than now) or counts past, the quest comes back with the run after the one the
 * record counts for, in one step.
 *
 * <p><b>An old record</b>, saved before the run tally existed, carries no run year: it belongs to run 1 of
 * the year of the run nearest its last finish by the event's dates as they stand now ({@code nearestRunYear},
 * the one place that places it), and holds one finish there. Every such record was saved for an event that
 * came round once a year. A record with no last finish belongs to no run.
 */
public final class PerRuns {

    /** The calendar dates no first year after it: a stored finish later than this is a corrupt value. */
    private static final int LAST_FINISH_YEAR = 9999;

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
     * Where a finish is recorded: the run it counts for, and how many finishes that run held before it.
     *
     * @param run    the run the finish counts for
     * @param before the finishes the record already held for that run; the finish being recorded is not in it
     */
    public record RunTally(@Nonnull RunKey run, int before) {
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
     * kept or set aside, or from where its number would fall when the year lacks it. So the run the wait names
     * always comes after the run it was asked from. False for the key's own run, and for every run of its year
     * when the calendar answers no run after it that year (none is left, or a source that knows no dates).
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
     * Where a finish at {@code nowMs} is recorded: {@link #runFor}, but never a run earlier than the one
     * {@code prior} already counts for (runs only move forward), and that run when none is going on or due;
     * with the finishes that run held before this one (the record's own tally when it is the record's run,
     * else none). Null when no run is going on, due or counted.
     */
    @Nullable
    public static RunTally runToRecord(@Nonnull CompletionRecord prior, @Nonnull Quest.Repeat.PerRun perRun,
            long nowMs, @Nonnull OccurrenceSource occurrences) {
        Occurrence run = runFor(perRun, nowMs, occurrences);
        RunKey counted = runOf(prior, perRun, occurrences);
        if (run != null && (counted == null || comesAfter(run, counted, perRun, occurrences))) {
            return new RunTally(RunKey.of(run), 0);
        }
        // The run going on or due is the record's own, or an earlier one; or none is: the record's run stands.
        return counted == null ? null : new RunTally(counted, heldIn(prior));
    }

    /**
     * How many finishes {@code record} holds for {@code run}. A record counted for a LATER run reads as every
     * finish {@code run} allows: runs only move forward, so an earlier run can never pay the quest again.
     */
    public static int spentIn(@Nonnull CompletionRecord record, @Nonnull Occurrence run,
            @Nonnull Quest.Repeat.PerRun perRun, @Nonnull OccurrenceSource occurrences) {
        RunKey counted = runOf(record, perRun, occurrences);
        if (counted == null) {
            return 0;
        }
        if (counted.is(run)) {
            return heldIn(record);
        }
        return comesAfter(run, counted, perRun, occurrences) ? 0 : perRun.times();
    }

    /** The finishes {@code record} holds for the run it counts for: its tally, or the one finish of an old record. */
    private static int heldIn(@Nonnull CompletionRecord record) {
        return record.runYear() != null ? record.runCount() : 1;
    }

    /**
     * The once-a-run half of the repeat rule: null while a run is going on and its tally is under
     * {@code Times}; otherwise the refusal, offerable again when the first run the player has not spent
     * starts (never, when none is left).
     */
    @Nullable
    static QuestLifecycle.RepeatCheck refusal(@Nonnull Quest.Repeat.PerRun perRun,
            @Nonnull CompletionRecord completions, long nowMs, @Nonnull OccurrenceSource occurrences) {
        Occurrence live = occurrences.live(perRun.event(), nowMs);
        if (live != null && spentIn(completions, live, perRun, occurrences) < perRun.times()) {
            return null;
        }
        Occurrence back = occurrences.next(perRun.event(), nowMs);
        if (back != null && spentIn(completions, back, perRun, occurrences) >= perRun.times()) {
            // The next run is spent: it is the record's own run (an owner moved it to start later than now), or
            // the record counts past it. Either way the record's run is the later of the two, and the quest comes
            // back with the run after it: one step, since that run comes after the record's by the same answer.
            RunKey counted = runOf(completions, perRun, occurrences);
            back = counted == null ? null : occurrences.after(perRun.event(), counted.year(), counted.number());
        }
        return new QuestLifecycle.RepeatCheck(false, QuestGates.REASON_RUN_SPENT,
                back == null ? Long.MAX_VALUE : back.startMs());
    }
}
