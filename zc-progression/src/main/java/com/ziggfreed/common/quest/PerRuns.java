package com.ziggfreed.common.quest;

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
 * <p><b>Runs are keyed (event, year)</b>, the year a run starts in, never by whether its days contain
 * now: a run forced on keeps its year, a run whose days an owner moved mid-run stays the run it was, and
 * every day of a run crossing the new year counts for the year it began in.
 *
 * <p><b>Runs only move forward.</b> A finish counts for the run going on, else the next one, and never for
 * a run earlier than the one its record already counts for; a record counted for a later run reads as
 * spent for every earlier one, so a run forced on after it cannot pay the quest again. A quest counts no
 * progress while it is not on offer, so a player only ever finishes it inside a run; only a close-out
 * from outside play lands between runs.
 *
 * <p><b>An old record</b>, saved before the run tally existed, carries no run year: it belongs to the run
 * whose days hold its last finish, and holds one finish there. A last finish no run's days hold belongs
 * to no run.
 */
public final class PerRuns {

    private PerRuns() {
    }

    /** The year of the run a finish at {@code nowMs} counts for: the one going on, else the next, else none. */
    @Nullable
    public static Integer yearFor(@Nonnull Quest.Repeat.PerRun perRun, long nowMs,
            @Nonnull OccurrenceSource occurrences) {
        Occurrence live = occurrences.live(perRun.event(), nowMs);
        if (live != null) {
            return live.year();
        }
        Occurrence next = occurrences.next(perRun.event(), nowMs);
        return next == null ? null : next.year();
    }

    /** The run year {@code record} counts for: its own, else (an old record) the run whose days hold its last finish. */
    @Nullable
    public static Integer runYearOf(@Nonnull CompletionRecord record, @Nonnull Quest.Repeat.PerRun perRun,
            long nowMs, @Nonnull OccurrenceSource occurrences) {
        if (record.runYear() != null) {
            return record.runYear();
        }
        long last = record.lastCompletionMs();
        if (last <= 0L) {
            return null;
        }
        for (Occurrence run : occurrences.history(perRun.event(), nowMs)) {
            if (run.contains(last)) {
                return run.year();
            }
        }
        return null;
    }

    /**
     * The year a finish at {@code nowMs} is recorded for: {@link #yearFor}, but never a run earlier than the
     * one {@code prior} already counts for (runs only move forward), and that run when no run is going on
     * or due.
     */
    @Nullable
    public static Integer yearToRecord(@Nonnull CompletionRecord prior, @Nonnull Quest.Repeat.PerRun perRun,
            long nowMs, @Nonnull OccurrenceSource occurrences) {
        Integer year = yearFor(perRun, nowMs, occurrences);
        Integer counted = runYearOf(prior, perRun, nowMs, occurrences);
        if (year == null) {
            return counted;
        }
        return counted != null && counted > year ? counted : year;
    }

    /**
     * How many finishes {@code record} holds for the run of {@code year}. A record counted for a LATER run
     * reads as every finish {@code year}'s run allows: runs only move forward, so an earlier run (one
     * forced on after the record was written) can never pay the quest again.
     */
    public static int spentIn(@Nonnull CompletionRecord record, int year, @Nonnull Quest.Repeat.PerRun perRun,
            long nowMs, @Nonnull OccurrenceSource occurrences) {
        Integer counted = runYearOf(record, perRun, nowMs, occurrences);
        if (counted == null || counted < year) {
            return 0;
        }
        if (counted > year) {
            return perRun.times();
        }
        return record.runYear() != null ? record.runCount() : 1;
    }

    /**
     * The once-a-run half of the repeat rule: null while a run is going on and its tally is under
     * {@code Times}; otherwise the refusal, offerable again when the event's next run starts (never, when it
     * has none).
     */
    @Nullable
    static QuestLifecycle.RepeatCheck refusal(@Nonnull Quest.Repeat.PerRun perRun,
            @Nonnull CompletionRecord completions, long nowMs, @Nonnull OccurrenceSource occurrences) {
        Occurrence live = occurrences.live(perRun.event(), nowMs);
        if (live != null && spentIn(completions, live.year(), perRun, nowMs, occurrences) < perRun.times()) {
            return null;
        }
        Occurrence next = occurrences.next(perRun.event(), nowMs);
        return new QuestLifecycle.RepeatCheck(false, QuestGates.REASON_RUN_SPENT,
                next == null ? Long.MAX_VALUE : next.startMs());
    }
}
