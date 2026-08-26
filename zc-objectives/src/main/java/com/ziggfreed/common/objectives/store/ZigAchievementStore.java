package com.ziggfreed.common.objectives.store;

import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.achievement.AchievementProgressStore;
import com.ziggfreed.common.achievement.AchievementStatus;
import com.ziggfreed.common.objectives.runtime.ProgressionDefaults;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * The library's DEFAULT achievement persistence, the peer of {@link ZigQuestStore} over the same
 * component: resolve the subject's {@link ZigProgressComponent} and delegate.
 *
 * <p>Two adapters rather than one because the two seams declare {@code status(Subject, String)} with
 * the same erasure and different return types, so no single class can implement both.
 *
 * <p>Same rules as its peer, and for the same reasons. A read never creates, a missing component
 * reads neutral and drops writes with one fine-level line. The component is taken from a
 * {@link ZigProgressComponent} the subject's handle answers for DIRECTLY where there is one, and
 * from a {@link ProgressHandle} otherwise, so a consumer supplying its own subject source keeps
 * these stores as THE store rather than bringing a second version of one player's state.
 * {@code markDirty} / {@code flush} fan out to whatever registered through
 * {@link ProgressionDefaults#onProgressDirty} / {@link ProgressionDefaults#onProgressFlush}.
 *
 * <p>Every other interface default is inherited - including the composite criterion key,
 * {@code clearAchievement}, and {@code usesReservedDelimiter} (whose default already rejects
 * everything {@link ProgressBlob} reserves, plus the criterion separator).
 */
public final class ZigAchievementStore implements AchievementProgressStore {

    /** The one instance, registered as the LIBRARY DEFAULT and outranked by any consumer's own. */
    public static final ZigAchievementStore INSTANCE = new ZigAchievementStore();

    private ZigAchievementStore() {
    }

    @Nullable
    private static ZigProgressComponent componentOf(@Nonnull Subject subject) {
        ZigProgressComponent direct = subject.handleAs(ZigProgressComponent.class);
        if (direct != null) {
            return direct;
        }
        ProgressHandle handle = subject.handleAs(ProgressHandle.class);
        return handle == null ? null : handle.component();
    }

    private static void dropped(@Nonnull Subject subject, @Nonnull String what) {
        SafeLog.fine("[progression] dropped an achievement " + what + " for '" + subject.name()
                + "': no progress component");
    }

    /** Fan the change out to every registered dirty listener. This store persists nothing itself. */
    @Override
    public void markDirty(@Nonnull Subject subject) {
        ProgressionDefaults.fireProgressDirty(subject);
    }

    /** Fan the transaction boundary out to every registered flush listener. */
    @Override
    public void flush(@Nonnull Subject subject) {
        ProgressionDefaults.fireProgressFlush(subject);
    }

    @Override
    public long progress(@Nonnull Subject subject, @Nonnull String key) {
        ZigProgressComponent component = componentOf(subject);
        return component == null ? 0L : component.achievementProgress(key);
    }

    @Override
    public void putProgress(@Nonnull Subject subject, @Nonnull String key, long value) {
        ZigProgressComponent component = componentOf(subject);
        if (component == null) {
            dropped(subject, "tally");
            return;
        }
        component.putAchievementProgress(key, value);
    }

    @Nonnull
    @Override
    public Set<String> progressKeys(@Nonnull Subject subject) {
        ZigProgressComponent component = componentOf(subject);
        return component == null ? Set.of() : component.achievementProgressKeys();
    }

    @Nonnull
    @Override
    public AchievementStatus status(@Nonnull Subject subject, @Nonnull String achievementId) {
        ZigProgressComponent component = componentOf(subject);
        return component == null ? AchievementStatus.LOCKED : component.achievementStatus(achievementId);
    }

    @Override
    public void setStatus(@Nonnull Subject subject, @Nonnull String achievementId,
            @Nonnull AchievementStatus status) {
        ZigProgressComponent component = componentOf(subject);
        if (component == null) {
            dropped(subject, "status");
            return;
        }
        component.setAchievementStatus(achievementId, status);
    }

    @Nonnull
    @Override
    public Set<String> knownAchievementIds(@Nonnull Subject subject) {
        ZigProgressComponent component = componentOf(subject);
        return component == null ? Set.of() : component.knownAchievementIds();
    }

    @Override
    public long unlockedAt(@Nonnull Subject subject, @Nonnull String achievementId) {
        ZigProgressComponent component = componentOf(subject);
        return component == null ? 0L : component.achievementUnlockedAt(achievementId);
    }

    @Override
    public void setUnlockedAt(@Nonnull Subject subject, @Nonnull String achievementId, long epochMs) {
        ZigProgressComponent component = componentOf(subject);
        if (component == null) {
            dropped(subject, "unlock instant");
            return;
        }
        component.setAchievementUnlockedAt(achievementId, epochMs);
    }

    @Nonnull
    @Override
    public AchievementStatus milestoneStatus(@Nonnull Subject subject, int threshold) {
        ZigProgressComponent component = componentOf(subject);
        return component == null ? AchievementStatus.LOCKED : component.milestoneStatus(threshold);
    }

    @Override
    public void setMilestoneStatus(@Nonnull Subject subject, int threshold,
            @Nonnull AchievementStatus status) {
        ZigProgressComponent component = componentOf(subject);
        if (component == null) {
            dropped(subject, "milestone status");
            return;
        }
        component.setMilestoneStatus(threshold, status);
    }

    @Nonnull
    @Override
    public Set<Integer> knownMilestones(@Nonnull Subject subject) {
        ZigProgressComponent component = componentOf(subject);
        return component == null ? Set.of() : component.knownMilestones();
    }

    @Nonnull
    @Override
    public Map<String, Long> pins(@Nonnull Subject subject) {
        ZigProgressComponent component = componentOf(subject);
        return component == null ? Map.of() : component.achievementPins();
    }

    @Override
    public void setPin(@Nonnull Subject subject, @Nonnull String achievementId, long pinnedAtMs) {
        ZigProgressComponent component = componentOf(subject);
        if (component == null) {
            dropped(subject, "pin");
            return;
        }
        component.setAchievementPin(achievementId, pinnedAtMs);
    }

    @Override
    public boolean clearPin(@Nonnull Subject subject, @Nonnull String achievementId) {
        ZigProgressComponent component = componentOf(subject);
        if (component == null) {
            dropped(subject, "unpin");
            return false;
        }
        return component.clearAchievementPin(achievementId);
    }
}
