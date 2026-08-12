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
 * <p>There are exactly four kinds of per-player state:
 * <ul>
 *   <li><b>status</b> per quest ({@link QuestStatus}) - note {@link QuestStatus#ON_COOLDOWN} is
 *   never STORED, it is computed by {@link QuestLifecycle#effectiveStatus};
 *   <li><b>progress payload</b> per quest - one opaque string, packed by
 *   {@link QuestProgressPayload}. A store persists it verbatim and never parses it;
 *   <li><b>cooldown stamp</b> per quest - epoch milliseconds, {@code 0} for none;
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
     * Forget this quest for this player entirely - status, progress, cooldown stamp, and pin. Used
     * when a quest is abandoned and when a finished repeatable comes back around, so it starts from
     * pristine rather than half-remembered.
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
