package com.ziggfreed.common.objectives.title.page;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.AchievementEngine;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.objectives.title.TitleRewardKind;
import com.ziggfreed.common.progress.ObjectiveProgressState;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.kit.Progress;

/**
 * Which achievements give each title, read off the achievement catalogue: an achievement gives a title when one of
 * its rewards, paid on unlock or on collect, is of the {@code Title} kind naming it ({@link TitleRewardKind#titleOf},
 * either parameter spelling). The picker's not-earned line names the first of them the player can see, with how far
 * along they are (or that the title waits on their collecting it), so "what gives this title" never needs authoring
 * twice.
 *
 * <p>Pure over what it is handed: built from a catalogue, asked with an engine and a subject. An achievement the
 * player cannot see (hidden, out of circulation, gated) is never named.
 */
public final class TitleSources {

    /**
     * The achievement a title comes from, the viewer's progress on it, and whether the title waits only on the
     * viewer collecting it: the achievement pays it on collect alone, and they have earned it but not collected.
     */
    public record Source(@Nonnull String achievementId, @Nonnull Progress progress, boolean collect) {

        public Source {
            Objects.requireNonNull(achievementId, "achievementId");
            Objects.requireNonNull(progress, "progress");
        }

        /** A source the viewer is still working toward. */
        public Source(@Nonnull String achievementId, @Nonnull Progress progress) {
            this(achievementId, progress, false);
        }
    }

    /** Catalogue order: an achievement's sort order, then its id. */
    private static final Comparator<Achievement> ORDER =
            Comparator.comparingInt(Achievement::sortOrder).thenComparing(Achievement::id);

    /** Title id, lower-cased, to the achievements that give it, in {@link #ORDER}. */
    @Nonnull private final Map<String, List<Achievement>> granters;

    private TitleSources(@Nonnull Map<String, List<Achievement>> granters) {
        this.granters = granters;
    }

    /** Index {@code catalogue}: each achievement under every title its rewards give, each at most once per title. */
    @Nonnull
    public static TitleSources of(@Nonnull Collection<Achievement> catalogue) {
        List<Achievement> ordered = new ArrayList<>(catalogue);
        ordered.sort(ORDER);
        Map<String, List<Achievement>> index = new LinkedHashMap<>();
        for (Achievement achievement : ordered) {
            if (achievement == null) {
                continue;
            }
            for (String titleId : titlesOf(achievement)) {
                List<Achievement> list = index.computeIfAbsent(titleId, k -> new ArrayList<>());
                if (!list.contains(achievement)) {
                    list.add(achievement);
                }
            }
        }
        Map<String, List<Achievement>> frozen = new LinkedHashMap<>();
        index.forEach((titleId, list) -> frozen.put(titleId, List.copyOf(list)));
        return new TitleSources(Map.copyOf(frozen));
    }

    /** The achievements that give {@code titleId}, in catalogue order; empty when none does. */
    @Nonnull
    public List<Achievement> granters(@Nonnull String titleId) {
        return granters.getOrDefault(key(titleId), List.of());
    }

    /**
     * The source shown for {@code titleId}: the first achievement giving it that {@code visible} accepts, with
     * {@code progress}'s reading of it; null when none does.
     */
    @Nullable
    public Source source(@Nonnull String titleId, @Nonnull Predicate<Achievement> visible,
            @Nonnull Function<Achievement, Progress> progress) {
        return source(titleId, visible, progress, achievement -> false);
    }

    /**
     * The source shown for {@code titleId}: the first achievement giving it that {@code visible} accepts and
     * {@code collect} says waits only on a collect, marked so, since collecting it is the one step left; else the
     * first {@code visible} accepts, as {@link #source(String, Predicate, Function)} reads it; null when none does.
     */
    @Nullable
    public Source source(@Nonnull String titleId, @Nonnull Predicate<Achievement> visible,
            @Nonnull Function<Achievement, Progress> progress, @Nonnull Predicate<Achievement> collect) {
        Achievement first = null;
        for (Achievement achievement : granters(titleId)) {
            if (!visible.test(achievement)) {
                continue;
            }
            if (collect.test(achievement)) {
                return new Source(achievement.id(), progress.apply(achievement), true);
            }
            if (first == null) {
                first = achievement;
            }
        }
        return first == null ? null : new Source(first.id(), progress.apply(first), false);
    }

    /**
     * The source as {@code subject} sees it: the first giver {@code engine} lists for them that waits only on their
     * collecting it ({@link #awaitsCollect}), else the first it lists, with their progress.
     */
    @Nullable
    public Source source(@Nonnull String titleId, @Nonnull AchievementEngine engine, @Nonnull Subject subject) {
        return source(titleId, achievement -> engine.isVisible(subject, achievement),
                achievement -> progress(engine, subject, achievement),
                achievement -> awaitsCollect(engine, subject, achievement, titleId));
    }

    /**
     * Whether {@code subject} gets {@code titleId} from {@code achievement} only by collecting it: the achievement
     * pays the title on collect and not on unlock, and they have earned it but not collected it yet.
     */
    public static boolean awaitsCollect(@Nonnull AchievementEngine engine, @Nonnull Subject subject,
            @Nonnull Achievement achievement, @Nonnull String titleId) {
        String id = key(titleId);
        return TitleRewardKind.titlesIn(achievement.claimRewards()).contains(id)
                && !TitleRewardKind.titlesIn(achievement.autoRewards()).contains(id)
                && engine.isUnlocked(subject, achievement.id())
                && !engine.isClaimed(subject, achievement.id());
    }

    /**
     * How far {@code subject} is along {@code achievement}, as one count: an earned one reads complete; a capstone
     * counts its children earned; one criterion counts toward its target (stopping there); several count the
     * criteria met.
     */
    @Nonnull
    public static Progress progress(@Nonnull AchievementEngine engine, @Nonnull Subject subject,
            @Nonnull Achievement achievement) {
        boolean earned = engine.isUnlocked(subject, achievement.id());
        if (!achievement.isMeta() && achievement.criteria().size() == 1) {
            ObjectiveProgressState state = engine.progressOf(subject, achievement, 0);
            long required = Math.max(1, state.required());
            return new Progress(earned ? required : Math.max(0, Math.min(required, state.current())), required);
        }
        AchievementEngine.CriterionTally tally = engine.tally(subject, achievement);
        return new Progress(earned ? tally.total() : tally.completed(), tally.total());
    }

    /** Every title {@code achievement}'s rewards give, lower-cased, in reward order. */
    @Nonnull
    private static List<String> titlesOf(@Nonnull Achievement achievement) {
        List<String> titles = new ArrayList<>();
        for (List<RewardSpec> rewards : List.of(achievement.autoRewards(), achievement.claimRewards())) {
            for (String titleId : TitleRewardKind.titlesIn(rewards)) {
                if (!titles.contains(titleId)) {
                    titles.add(titleId);
                }
            }
        }
        return titles;
    }

    @Nonnull
    private static String key(@Nonnull String titleId) {
        return titleId.trim().toLowerCase(Locale.ROOT);
    }
}
