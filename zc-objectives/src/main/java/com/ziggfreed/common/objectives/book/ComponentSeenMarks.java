package com.ziggfreed.common.objectives.book;

import java.util.Objects;
import java.util.function.LongSupplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.objectives.runtime.ProgressionDefaults;
import com.ziggfreed.common.objectives.store.ZigProgressComponent;
import com.ziggfreed.common.subject.Subject;

/**
 * The library's own {@link SeenMarks}, kept in the player's {@link ZigProgressComponent} (its {@code AchievementSeen}
 * leaf), installed by {@link ObjectiveBookBootstrap} as the marks every book's deps carry unless a consumer names its
 * own.
 *
 * <p>A category the player opened reads its own mark. One never opened reads the player's first look at the book (a
 * baseline written on that first read), so the marks arriving on a server lights nothing the player earned before:
 * only what is earned after their first look reads as new. A mark written is reported dirty
 * ({@code ProgressionDefaults.fireProgressDirty}) and never flushed, like any write that goes around the engines. A
 * subject with no component reads as seen just now ({@link Long#MAX_VALUE}) and remembers nothing.
 */
public final class ComponentSeenMarks implements SeenMarks {

    /** The marks on the wall clock. */
    public static final ComponentSeenMarks INSTANCE = new ComponentSeenMarks(System::currentTimeMillis);

    /** The baseline's key: the player's first look, read by every category they never opened. */
    static final String BASELINE = "*";

    private final LongSupplier clock;

    ComponentSeenMarks(@Nonnull LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public long seenAt(@Nullable Subject subject, @Nonnull String category) {
        ZigProgressComponent component = ZigProgressComponent.of(subject);
        if (component == null) {
            return Long.MAX_VALUE;
        }
        long own = component.achievementSeen(category);
        if (own > 0L) {
            return own;
        }
        long baseline = component.achievementSeen(BASELINE);
        if (baseline > 0L) {
            return baseline;
        }
        long now = clock.getAsLong();
        component.setAchievementSeen(BASELINE, now);
        ProgressionDefaults.fireProgressDirty(subject);
        return now;
    }

    @Override
    public void markSeen(@Nullable Subject subject, @Nonnull String category, long nowMs) {
        ZigProgressComponent component = ZigProgressComponent.of(subject);
        if (component == null || nowMs <= 0L) {
            return;
        }
        component.setAchievementSeen(category, nowMs);
        ProgressionDefaults.fireProgressDirty(subject);
    }
}
