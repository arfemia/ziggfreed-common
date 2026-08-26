package com.ziggfreed.common.achievement;

import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.subject.Subject;

/**
 * THE persistence seam. Everything the achievement engine knows about a subject lives behind this
 * interface, and the engine never sees the storage underneath - a component on an entity, a row in a
 * database, a map that dies with the round, all satisfy it identically.
 *
 * <p>There are five kinds of per-subject state:
 * <ul>
 *   <li><b>criterion progress</b> - one long per criterion, keyed
 *   {@code "<achievementId>#<criterionId>"} by {@link #criterionKey};
 *   <li><b>status</b> per achievement ({@link AchievementStatus});
 *   <li><b>unlock instant</b> per achievement - epoch milliseconds, {@code 0} for none;
 *   <li><b>milestone status</b> per points threshold;
 *   <li><b>pins</b> - the achievements the subject pinned, each with the instant they pinned it.
 * </ul>
 *
 * <p><b>The composite key is a DEFAULT here, so every store gets it right.</b> A store implements
 * the flat {@link #progress}/{@link #putProgress} pair and inherits the keying. The criterion id is
 * the AUTHORED key of the criterion (a legacy authoring layer that numbers its criteria simply has
 * decimal ids), so re-authoring an achievement's criteria never silently re-points a subject's
 * stored progress.
 *
 * <p><b>Id hygiene is the STORE's call, not the engine's</b> ({@link #usesReservedDelimiter}). The
 * engine cannot know which characters a given backing format chokes on, so it asks. The default
 * answer covers the criterion separator plus the separators a key-value store commonly uses; a store
 * with a different format overrides it, and a content validator calls it so a bad id is a load-time
 * finding rather than silently lost progress much later.
 *
 * <p>Implementations are called on whatever thread the consumer dispatches on and should be cheap;
 * {@link #markDirty} and {@link #flush} exist so a store that batches writes can be told where a
 * transaction boundary is (both no-ops by default).
 */
public interface AchievementProgressStore {

    /** Joins an achievement id to a criterion id inside one progress key. */
    String CRITERION_SEPARATOR = "#";

    /** The characters the default {@link #usesReservedDelimiter} rejects inside an id. */
    String DEFAULT_RESERVED_CHARACTERS = "|=:,#";

    /** The progress key one criterion is stored under. */
    @Nonnull
    static String criterionKey(@Nonnull String achievementId, @Nonnull String criterionId) {
        return achievementId + CRITERION_SEPARATOR + criterionId;
    }

    /** The raw stored tally under {@code key}, or {@code 0} when nothing is recorded. */
    long progress(@Nonnull Subject subject, @Nonnull String key);

    /** Store a raw tally. A value of {@code 0} REMOVES the key rather than storing a zero. */
    void putProgress(@Nonnull Subject subject, @Nonnull String key, long value);

    /** Every raw progress key this subject holds. Drives maintenance sweeps and per-id clearing. */
    @Nonnull
    Set<String> progressKeys(@Nonnull Subject subject);

    /** The stored status, or {@link AchievementStatus#LOCKED} when nothing is recorded. */
    @Nonnull
    AchievementStatus status(@Nonnull Subject subject, @Nonnull String achievementId);

    /** Record an achievement's status. */
    void setStatus(@Nonnull Subject subject, @Nonnull String achievementId,
                   @Nonnull AchievementStatus status);

    /** Every achievement id this subject has ANY recorded state for. */
    @Nonnull
    Set<String> knownAchievementIds(@Nonnull Subject subject);

    /** When it was earned, in epoch milliseconds, or {@code 0}. */
    long unlockedAt(@Nonnull Subject subject, @Nonnull String achievementId);

    /** Record when it was earned. */
    void setUnlockedAt(@Nonnull Subject subject, @Nonnull String achievementId, long epochMs);

    /** The stored status of a points milestone, or {@link AchievementStatus#LOCKED}. */
    @Nonnull
    AchievementStatus milestoneStatus(@Nonnull Subject subject, int threshold);

    /** Record a milestone's status. */
    void setMilestoneStatus(@Nonnull Subject subject, int threshold, @Nonnull AchievementStatus status);

    /** Every milestone threshold this subject has a recorded status for. */
    @Nonnull
    Set<Integer> knownMilestones(@Nonnull Subject subject);

    /** The subject's pins as {@code achievementId -> the instant it was pinned}, in any order. */
    @Nonnull
    Map<String, Long> pins(@Nonnull Subject subject);

    /** Pin an achievement at {@code pinnedAtMs}. Re-pinning an already-pinned one just re-stamps it. */
    void setPin(@Nonnull Subject subject, @Nonnull String achievementId, long pinnedAtMs);

    /** Drop a pin. Returns true when one was actually there. */
    boolean clearPin(@Nonnull Subject subject, @Nonnull String achievementId);

    /** One criterion's progress, under the composite key. See the class javadoc. */
    default long criterionProgress(@Nonnull Subject subject, @Nonnull String achievementId,
                                   @Nonnull String criterionId) {
        return progress(subject, criterionKey(achievementId, criterionId));
    }

    /** Record one criterion's progress under the composite key. */
    default void setCriterionProgress(@Nonnull Subject subject, @Nonnull String achievementId,
                                      @Nonnull String criterionId, long value) {
        putProgress(subject, criterionKey(achievementId, criterionId), value);
    }

    /**
     * Forget one achievement for this subject entirely - every criterion's progress (including the
     * legacy bare key), its status, its unlock instant, and its pin.
     */
    default void clearAchievement(@Nonnull Subject subject, @Nonnull String achievementId) {
        String prefix = achievementId + CRITERION_SEPARATOR;
        for (String key : Set.copyOf(progressKeys(subject))) {
            if (key.equals(achievementId) || key.startsWith(prefix)) {
                putProgress(subject, key, 0L);
            }
        }
        setStatus(subject, achievementId, AchievementStatus.LOCKED);
        setUnlockedAt(subject, achievementId, 0L);
        clearPin(subject, achievementId);
    }

    /**
     * Forget EVERYTHING this store holds for the subject: every achievement's progress, status,
     * unlock instant and pin, plus every points milestone. An administrator's start-over.
     *
     * <p>The default walks the per-item operations above, so it is correct for any implementation;
     * one that can drop a subject's whole record at once may override it. The bare progress sweep
     * after the id walk is deliberate: it catches a key no derived id reaches, so a stray entry
     * cannot outlive the start-over.
     */
    default void clearAll(@Nonnull Subject subject) {
        for (String achievementId : Set.copyOf(knownAchievementIds(subject))) {
            clearAchievement(subject, achievementId);
        }
        for (String key : Set.copyOf(progressKeys(subject))) {
            putProgress(subject, key, 0L);
        }
        for (Integer threshold : Set.copyOf(knownMilestones(subject))) {
            setMilestoneStatus(subject, threshold.intValue(), AchievementStatus.LOCKED);
        }
    }

    /**
     * Does {@code id} contain a character this store's format reserves, making it unsafe as an
     * achievement id? The default rejects {@link #DEFAULT_RESERVED_CHARACTERS} plus any blank id.
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

    /** Note that this subject's state changed, for a store that batches writes. No-op by default. */
    default void markDirty(@Nonnull Subject subject) {
    }

    /** Commit this subject's pending writes now, at a transaction boundary. No-op by default. */
    default void flush(@Nonnull Subject subject) {
    }
}
