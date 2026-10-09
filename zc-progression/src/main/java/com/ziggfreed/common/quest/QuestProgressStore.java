package com.ziggfreed.common.quest;

import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.occurrence.Occurrence;
import com.ziggfreed.common.subject.Subject;

/**
 * THE persistence seam. Everything the engine knows about a player lives behind this interface, and
 * the engine never sees the storage underneath - a component on an entity, a row in a database, a
 * map that dies with the round, all satisfy it identically.
 *
 * <p>There are exactly five kinds of per-player state:
 * <ul>
 *   <li><b>status</b> per quest ({@link QuestStatus}) - note {@link QuestStatus#ON_COOLDOWN} is
 *   never STORED, it is computed by {@link QuestLifecycle#effectiveStatus};
 *   <li><b>progress payload</b> per quest - one opaque string, packed by
 *   {@link QuestProgressPayload}. A store persists it verbatim and never parses it;
 *   <li><b>cooldown stamp</b> per quest - epoch milliseconds, {@code 0} for none;
 *   <li><b>completion record</b> per quest ({@link CompletionRecord}) - when the quest was last
 *   finished, how many times inside the current calendar window, how many times ever, and how many
 *   times the reward was actually COLLECTED. Optional: a store that cannot remember it says so
 *   through {@link #recordsCompletions()} and the calendar and lifetime knobs on
 *   {@link Quest.Repeat} stay inert;
 *   <li><b>tracked pins</b> - the quests the player pinned, each with the instant they pinned it.
 * </ul>
 *
 * <p><b>Id hygiene is the STORE's call, not the engine's</b> ({@link #usesReservedDelimiter}). The
 * engine cannot know which characters a given backing format chokes on, so it asks. The default
 * answer covers the shipped payload format plus the separators a key-value store commonly uses; a
 * store with a different format overrides it, and a content validator calls it so a bad id is a
 * load-time finding rather than silently truncated progress much later.
 *
 * <p>Implementations are called on whatever thread the consumer dispatches on and should be cheap;
 * {@link #markDirty} and {@link #flush} exist so a store that batches writes can be told where a
 * transaction boundary is (both no-ops by default).
 */
public interface QuestProgressStore {

    /** The characters the default {@link #usesReservedDelimiter} rejects inside an id. */
    String DEFAULT_RESERVED_CHARACTERS = "|=:,";

    /**
     * What a player's completions of ONE quest look like: when they last finished it, how many times
     * inside the calendar window that instant falls in, how many times in total, and how many of
     * those completions the player actually COLLECTED.
     *
     * <p>The window tally is deliberately NOT swept when a window rolls over. It is read against
     * {@link #lastCompletionMs()}, so a tally left over from an earlier window simply counts as
     * zero - which means nothing has to walk every player's quests on a timer to keep it honest.
     *
     * <p><b>Finished and collected are two different numbers.</b> A quest that does not claim itself
     * parks when its objectives are met and waits for the player to come and take the reward, so a
     * count of FINISHES answers "the objectives are done" while a count of CLAIMS answers "the
     * reward is in their hands". {@link #totalCount()} is the first; {@link #claimedCount()} is the
     * second, and it is what a "come back once you have run this ten times" reading is written
     * against. The repeat rules read the FINISH counts: a parked quest is not offered and cannot be
     * accepted, so no PLAYER can make the two readings disagree at a repeat decision, and the finish
     * is the safe half to cap on where something deliberately reaches past that (a scripted or
     * administrative accept, a re-arm) - the run happened and spent its slot whether or not anybody
     * came back for the reward.
     *
     * <p><b>Collected can never exceed finished</b>, and the constructor enforces it rather than
     * asking every writer and every decoder to. Nothing that raises the collected tally has to check
     * the other one, and a record that lost the distinction (one built by
     * {@link CompletionRecord#withoutCollectedTally}) cannot be talked into counting one collection
     * twice.
     *
     * <p><b>A once-a-run quest also keeps its run</b>: {@code runYear} and {@code runNumber} name the run
     * of its event its last finish counted for (the year it starts in, and the number its event's dates
     * name it by within that year, from 1: an identity, never a place in time), and {@code runCount} how
     * many finishes that run holds. {@code spentRuns} is the set of the year's OTHER runs the record has
     * spent, by number: bit {@code n - 1} for run {@code n}, from 1 to {@link Occurrence#MAX_NUMBER}. A run
     * joins it when the record moves on to another run of the same year, whatever its tally was, and the
     * run counted now is never in it (its own tally says whether it is spent). They are null, 0, 0 and none
     * for any other quest and for a record saved before the tally existed, which belongs to run 1 of the
     * year of the run nearest its last finish ({@link PerRuns}).
     */
    record CompletionRecord(long lastCompletionMs, int periodCount, int totalCount, int claimedCount,
                            @Nullable Integer runYear, int runCount, int runNumber, long spentRuns) {

        /** Every run number a year can have, as a set: bits 0 to {@link Occurrence#MAX_NUMBER} - 1. */
        private static final long EVERY_RUN = (1L << Occurrence.MAX_NUMBER) - 1L;

        /** Nothing recorded: never finished, nothing spent, nothing counted, nothing collected. */
        public static final CompletionRecord NONE = new CompletionRecord(0L, 0, 0, 0);

        public CompletionRecord {
            lastCompletionMs = Math.max(0L, lastCompletionMs);
            periodCount = Math.max(0, periodCount);
            totalCount = Math.max(0, totalCount);
            claimedCount = Math.min(Math.max(0, claimedCount), totalCount);
            runCount = runYear == null ? 0 : Math.max(0, runCount);
            runNumber = runYear == null ? 0 : Math.max(1, runNumber);
            spentRuns = runYear == null ? 0L : spentRuns & EVERY_RUN & ~runBit(runNumber);
        }

        /**
         * A record of run {@code runNumber} that has spent no other run of its year. Copying a record must use
         * the full form, never this one: it would forget the runs the record spent.
         */
        public CompletionRecord(long lastCompletionMs, int periodCount, int totalCount, int claimedCount,
                @Nullable Integer runYear, int runCount, int runNumber) {
            this(lastCompletionMs, periodCount, totalCount, claimedCount, runYear, runCount, runNumber, 0L);
        }

        /**
         * A record of a year's first run (or one that keeps no run tally, with a null {@code runYear}). Copying a
         * record must use the full form, never this one: it would forget the run number and the runs spent.
         */
        public CompletionRecord(long lastCompletionMs, int periodCount, int totalCount, int claimedCount,
                @Nullable Integer runYear, int runCount) {
            this(lastCompletionMs, periodCount, totalCount, claimedCount, runYear, runCount, 1);
        }

        /** Run {@code number} as a member of {@link #spentRuns()}; nothing for a number no run takes. */
        static long runBit(int number) {
            return number >= 1 && number <= Occurrence.MAX_NUMBER ? 1L << (number - 1) : 0L;
        }

        /** Has the record spent run {@code number} of its year, other than the run it counts now? */
        public boolean spentRun(int number) {
            return (spentRuns & runBit(number)) != 0L;
        }

        /** A record that keeps no run tally: a quest with no once-a-run rule, or a value saved before the tally existed. */
        public CompletionRecord(long lastCompletionMs, int periodCount, int totalCount, int claimedCount) {
            this(lastCompletionMs, periodCount, totalCount, claimedCount, null, 0);
        }

        /**
         * A record with no claimed count of its own: every finish counts as collected. Two things
         * reach for it, and neither is an ordinary writer.
         *
         * <p>The first is a STORE'S DECODER reading a value with no fourth field, which is a
         * development-tree courtesy and nothing wider. Every release from zc 2.1.0 on writes four
         * numbers, so a released save carries its last finish and both tallies that way (and is read
         * as it was written); a release before 2.1.0 wrote no completion record at all, so a save from
         * one reads as nothing recorded and simply starts counting from the first finish or collection
         * after the upgrade. A three-number value exists only where a tree was run against a
         * development build from before 2.1.0, and reading it as nothing collected would take a
         * completed prerequisite away from a player who had earned it under the rule that build was
         * written under, where a finish WAS the payout.
         *
         * <p>The second is a TEST FIXTURE standing in for a player with nothing left uncollected,
         * which is the shape most cases about the repeat rules want. What is NOT sanctioned is
         * ordinary production construction, and that is why this is a NAMED factory rather than a
         * three-argument constructor: "every finish was collected" is a real claim about a player's
         * history, and a call shape indistinguishable from ordinary construction would let a future
         * writer make it by accident. Here the meaning travels to the call site.
         *
         * <p>The one thing such a value cannot say is that its last run was still PARKED when it was
         * written, so a reward waiting to be collected reads as already collected until somebody
         * takes it. Collecting it then adds nothing rather than counting it twice, because the
         * collected tally is clamped to the finished one.
         */
        @Nonnull
        public static CompletionRecord withoutCollectedTally(long lastCompletionMs, int periodCount,
                                                             int totalCount) {
            return new CompletionRecord(lastCompletionMs, periodCount, totalCount, totalCount);
        }

        /**
         * True when this player has never finished the quest. The collected tally is not tested,
         * because it cannot be positive here: the constructor clamps it to the finished tally, so
         * nothing finished means nothing collected.
         */
        public boolean isEmpty() {
            return lastCompletionMs <= 0L && periodCount <= 0 && totalCount <= 0;
        }
    }

    /**
     * This player's completions of this quest, or {@link CompletionRecord#NONE}. The default answers
     * NONE, which is what a store that cannot remember them has to say.
     */
    @Nonnull
    default CompletionRecord completions(@Nonnull Subject subject, @Nonnull String questId) {
        return CompletionRecord.NONE;
    }

    /**
     * Record them. Passing {@link CompletionRecord#NONE} is the deliberate wipe - an administrator
     * starting a player over on this quest. The default drops the write, matching a store that does
     * not carry the state.
     */
    default void setCompletions(@Nonnull Subject subject, @Nonnull String questId,
                                @Nonnull CompletionRecord record) {
    }

    /**
     * Can this store REMEMBER completions? A store that cannot leaves the calendar allowance and the
     * lifetime cap on {@link Quest.Repeat} inert, and the engine says so once at load rather than
     * letting content quietly not work. The honest capability probe, exactly like
     * {@link #usesReservedDelimiter}: the STORE knows what it can hold, the engine asks.
     */
    default boolean recordsCompletions() {
        return false;
    }

    /** The stored status, or {@link QuestStatus#NOT_STARTED} when nothing is recorded. */
    @Nonnull
    QuestStatus status(@Nonnull Subject subject, @Nonnull String questId);

    /** Record a quest's status. Never called with {@link QuestStatus#ON_COOLDOWN}. */
    void setStatus(@Nonnull Subject subject, @Nonnull String questId, @Nonnull QuestStatus status);

    /** The packed progress payload, or null when the player has none for this quest. */
    @Nullable
    String progressPayload(@Nonnull Subject subject, @Nonnull String questId);

    /** Store a packed progress payload verbatim. */
    void putProgressPayload(@Nonnull Subject subject, @Nonnull String questId, @Nonnull String payload);

    /** The cooldown stamp in epoch milliseconds, or {@code 0} when there is none. */
    long cooldownStamp(@Nonnull Subject subject, @Nonnull String questId);

    /** Record a cooldown stamp in epoch milliseconds. */
    void setCooldownStamp(@Nonnull Subject subject, @Nonnull String questId, long epochMs);

    /** Every quest id this player has ANY recorded state for. Drives maintenance sweeps. */
    @Nonnull
    Set<String> knownQuestIds(@Nonnull Subject subject);

    /**
     * Re-arm this quest for this player - status, progress, cooldown stamp, and pin all go. Used
     * when a quest is abandoned and when a finished repeatable comes back around, so it starts from
     * pristine rather than half-remembered.
     *
     * <p><b>The {@link CompletionRecord} deliberately SURVIVES.</b> Both callers are moments the
     * player is about to play the quest again, and a lifetime cap that a re-arm wiped would be a cap
     * nobody could ever reach; the calendar tally has the same problem. The deliberate wipe is
     * {@code setCompletions(subject, questId, CompletionRecord.NONE)}, which is an administrator
     * starting somebody over rather than a quest coming round.
     */
    void clearQuest(@Nonnull Subject subject, @Nonnull String questId);

    /** The player's pins as {@code questId -> the instant it was pinned}, in any order. */
    @Nonnull
    Map<String, Long> trackedPins(@Nonnull Subject subject);

    /** Pin a quest at {@code pinnedAtMs}. Re-pinning an already-pinned quest just re-stamps it. */
    void setTrackedPin(@Nonnull Subject subject, @Nonnull String questId, long pinnedAtMs);

    /** Drop a pin. Returns true when one was actually there. */
    boolean clearTrackedPin(@Nonnull Subject subject, @Nonnull String questId);

    /**
     * Does {@code id} contain a character this store's format reserves, making it unsafe as a quest
     * or objective id? The default rejects {@link #DEFAULT_RESERVED_CHARACTERS} plus any blank id.
     */
    default boolean usesReservedDelimiter(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return true;
        }
        for (int i = 0; i < DEFAULT_RESERVED_CHARACTERS.length(); i++) {
            if (id.indexOf(DEFAULT_RESERVED_CHARACTERS.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** Note that this player's state changed, for a store that batches writes. No-op by default. */
    default void markDirty(@Nonnull Subject subject) {
    }

    /** Commit this player's pending writes now, at a transaction boundary. No-op by default. */
    default void flush(@Nonnull Subject subject) {
    }
}
