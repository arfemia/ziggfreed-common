package com.ziggfreed.common.achievement;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;

import com.ziggfreed.common.subject.Subject;

/**
 * A complete {@link AchievementProgressStore} that keeps everything in memory, keyed by
 * {@link Subject#id()}.
 *
 * <p>Two real uses: unit tests, and any consumer whose achievement state is genuinely meant to die
 * with the session. A consumer that needs it to survive a disconnect writes its own store against
 * the same interface instead - and inherits the composite-key and legacy-fallback rules from the
 * interface's defaults, so only the flat get and put are its own business.
 *
 * <p>Backed by {@link ConcurrentHashMap} so a dispatch and a read from another thread cannot corrupt
 * each other, though the engine's own operations still expect to be serialized by the consumer.
 */
public final class InMemoryAchievementProgressStore implements AchievementProgressStore {

    private static final class SubjectState {
        private final Map<String, Long> progress = new ConcurrentHashMap<>();
        private final Map<String, AchievementStatus> statuses = new ConcurrentHashMap<>();
        private final Map<String, Long> unlockedAt = new ConcurrentHashMap<>();
        private final Map<Integer, AchievementStatus> milestones = new ConcurrentHashMap<>();
        private final Map<String, Long> pins = new ConcurrentHashMap<>();
    }

    private final Map<UUID, SubjectState> subjects = new ConcurrentHashMap<>();

    @Nonnull
    private SubjectState state(@Nonnull Subject subject) {
        return subjects.computeIfAbsent(subject.id(), key -> new SubjectState());
    }

    @Override
    public long progress(@Nonnull Subject subject, @Nonnull String key) {
        Long value = state(subject).progress.get(key);
        return value == null ? 0L : value;
    }

    @Override
    public void putProgress(@Nonnull Subject subject, @Nonnull String key, long value) {
        if (value == 0L) {
            state(subject).progress.remove(key);
        } else {
            state(subject).progress.put(key, value);
        }
    }

    @Override
    @Nonnull
    public Set<String> progressKeys(@Nonnull Subject subject) {
        return Set.copyOf(state(subject).progress.keySet());
    }

    @Override
    @Nonnull
    public AchievementStatus status(@Nonnull Subject subject, @Nonnull String achievementId) {
        AchievementStatus status = state(subject).statuses.get(achievementId);
        return status == null ? AchievementStatus.LOCKED : status;
    }

    @Override
    public void setStatus(@Nonnull Subject subject, @Nonnull String achievementId,
                          @Nonnull AchievementStatus status) {
        if (status == AchievementStatus.LOCKED) {
            state(subject).statuses.remove(achievementId);
        } else {
            state(subject).statuses.put(achievementId, status);
        }
    }

    @Override
    @Nonnull
    public Set<String> knownAchievementIds(@Nonnull Subject subject) {
        SubjectState subjectState = state(subject);
        Set<String> ids = new LinkedHashSet<>(subjectState.statuses.keySet());
        ids.addAll(subjectState.unlockedAt.keySet());
        for (String key : subjectState.progress.keySet()) {
            int separator = key.indexOf(CRITERION_SEPARATOR);
            ids.add(separator > 0 ? key.substring(0, separator) : key);
        }
        return ids;
    }

    @Override
    public long unlockedAt(@Nonnull Subject subject, @Nonnull String achievementId) {
        Long stamp = state(subject).unlockedAt.get(achievementId);
        return stamp == null ? 0L : stamp;
    }

    @Override
    public void setUnlockedAt(@Nonnull Subject subject, @Nonnull String achievementId, long epochMs) {
        if (epochMs <= 0L) {
            state(subject).unlockedAt.remove(achievementId);
        } else {
            state(subject).unlockedAt.put(achievementId, epochMs);
        }
    }

    @Override
    @Nonnull
    public AchievementStatus milestoneStatus(@Nonnull Subject subject, int threshold) {
        AchievementStatus status = state(subject).milestones.get(threshold);
        return status == null ? AchievementStatus.LOCKED : status;
    }

    @Override
    public void setMilestoneStatus(@Nonnull Subject subject, int threshold,
                                   @Nonnull AchievementStatus status) {
        if (status == AchievementStatus.LOCKED) {
            state(subject).milestones.remove(threshold);
        } else {
            state(subject).milestones.put(threshold, status);
        }
    }

    @Override
    @Nonnull
    public Set<Integer> knownMilestones(@Nonnull Subject subject) {
        return Set.copyOf(state(subject).milestones.keySet());
    }

    @Override
    @Nonnull
    public Map<String, Long> pins(@Nonnull Subject subject) {
        return new HashMap<>(state(subject).pins);
    }

    @Override
    public void setPin(@Nonnull Subject subject, @Nonnull String achievementId, long pinnedAtMs) {
        state(subject).pins.put(achievementId, pinnedAtMs);
    }

    @Override
    public boolean clearPin(@Nonnull Subject subject, @Nonnull String achievementId) {
        return state(subject).pins.remove(achievementId) != null;
    }

    /** Forget one subject entirely (they left, the round ended). */
    public void forget(@Nonnull Subject subject) {
        subjects.remove(subject.id());
    }

    /** Forget everybody. */
    public void clear() {
        subjects.clear();
    }
}
