package com.ziggfreed.common.quest;

import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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
 *   finished, how many times inside the current calendar window, and how many times ever. Optional:
 *   a store that cannot remember it says so through {@link #recordsCompletions()} and the calendar
 *   and lifetime knobs on {@link Quest.Repeat} stay inert;
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
     * inside the calendar window that instant falls in, and how many times in total.
     *
     * <p>The window tally is deliberately NOT swept when a window rolls over. It is read against
     * {@link #lastCompletionMs()}, so a tally left over from an earlier window simply counts as
     * zero - which means nothing has to walk every player's quests on a timer to keep it honest.
     */
    record CompletionRecord(long lastCompletionMs, int periodCount, int totalCount) {

        /** Nothing recorded: never finished, nothing spent, nothing counted. */
        public static final CompletionRecord NONE = new CompletionRecord(0L, 0, 0);

        public CompletionRecord {
            lastCompletionMs = Math.max(0L, lastCompletionMs);
            periodCount = Math.max(0, periodCount);
            totalCount = Math.max(0, totalCount);
        }

        /** True when this player has never finished the quest. */
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
