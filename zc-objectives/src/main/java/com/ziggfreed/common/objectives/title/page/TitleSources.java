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
import com.ziggfreed.common.progress.ObjectiveArithmetic;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.ObjectiveProgressState;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.kit.Progress;

/**
 * Which achievements and quests give each title, read off their catalogues: one gives a title when one of its
 * rewards, paid when it is earned or on collect, is of the {@code Title} kind naming it ({@link TitleRewardKind#titleOf},
 * either parameter spelling). The picker's not-earned line names the first of them the player can see, with how far
 * along they are (or that the title waits on their collecting it), so "what gives this title" never needs authoring
 * twice.
 *
 * <p>Pure over what it is handed: built from the catalogues, asked with an engine and a subject. An achievement or
 * quest the player cannot see (hidden, out of circulation, gated) is never named.
 */
public final class TitleSources {

    /** What a title comes from. */
    public enum Kind {
        ACHIEVEMENT,
        QUEST
    }

    /**
     * The achievement or quest a title comes from, the viewer's progress on it, and whether the title waits only on
     * the viewer collecting it: they have finished it, and it holds the title until they collect.
     */
    public record Source(@Nonnull Kind kind, @Nonnull String id, @Nonnull Progress progress, boolean collect) {

        public Source {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(progress, "progress");
        }
    }

    /** Achievement catalogue order: an achievement's sort order, then its id. */
    private static final Comparator<Achievement> ORDER =
            Comparator.comparingInt(Achievement::sortOrder).thenComparing(Achievement::id);

    /** Quest catalogue order: a quest's list order, then its id. */
    private static final Comparator<Quest> QUEST_ORDER =
            Comparator.comparingInt(Quest::listOrder).thenComparing(Quest::id);

    /** Title id, lower-cased, to the achievements that give it, in {@link #ORDER}. */
    @Nonnull private final Map<String, List<Achievement>> granters;

    /** Title id, lower-cased, to the quests that give it, in {@link #QUEST_ORDER}. */
    @Nonnull private final Map<String, List<Quest>> questGranters;

    private TitleSources(@Nonnull Map<String, List<Achievement>> granters,
            @Nonnull Map<String, List<Quest>> questGranters) {
        this.granters = granters;
        this.questGranters = questGranters;
    }

    /** Index the achievement {@code catalogue} alone, with no quest giving any title. */
    @Nonnull
    public static TitleSources of(@Nonnull Collection<Achievement> catalogue) {
        return of(catalogue, List.of());
    }

    /**
     * Index both catalogues: each achievement and each quest under every title its rewards give, each at most once
     * per title.
     */
    @Nonnull
    public static TitleSources of(@Nonnull Collection<Achievement> achievements, @Nonnull Collection<Quest> quests) {
        return new TitleSources(index(achievements, ORDER, TitleSources::titlesOf),
                index(quests, QUEST_ORDER, quest -> TitleRewardKind.titlesIn(quest.rewards())));
    }

    /** The achievements that give {@code titleId}, in catalogue order; empty when none does. */
    @Nonnull
    public List<Achievement> granters(@Nonnull String titleId) {
        return granters.getOrDefault(key(titleId), List.of());
    }

    /** The quests that give {@code titleId}, in catalogue order; empty when none does. */
    @Nonnull
    public List<Quest> questGranters(@Nonnull String titleId) {
        return questGranters.getOrDefault(key(titleId), List.of());
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
        return pick(Kind.ACHIEVEMENT, granters(titleId), Achievement::id, visible, progress, collect);
    }

    /**
     * The achievement source as {@code subject} sees it: the first giver {@code engine} lists for them that waits
     * only on their collecting it ({@link #awaitsCollect}), else the first it lists, with their progress.
     */
    @Nullable
    public Source source(@Nonnull String titleId, @Nonnull AchievementEngine engine, @Nonnull Subject subject) {
        return source(titleId, achievement -> engine.isVisible(subject, achievement),
                achievement -> progress(engine, subject, achievement),
                achievement -> awaitsCollect(engine, subject, achievement, titleId));
    }

    /**
     * The quest source as {@code subject} sees it: the first giver {@code engine} lists for them that they have
     * finished and not collected (a parked quest holds every reward, its title included), else the first it lists,
     * with their progress.
     */
    @Nullable
    public Source source(@Nonnull String titleId, @Nonnull QuestEngine engine, @Nonnull Subject subject) {
        return pick(Kind.QUEST, questGranters(titleId), Quest::id, quest -> engine.isVisible(subject, quest),
                quest -> progress(engine, subject, quest),
                quest -> engine.status(subject, quest) == QuestStatus.COMPLETED_UNCLAIMED);
    }

    /**
     * The one shown of a title's achievement source and its quest source: whichever waits only on a collect, the
     * achievement first, since collecting is the one step left; else the achievement; else the quest.
     */
    @Nullable
    public static Source first(@Nullable Source achievement, @Nullable Source quest) {
        if (achievement != null && achievement.collect()) {
            return achievement;
        }
        if (quest != null && quest.collect()) {
            return quest;
        }
        return achievement != null ? achievement : quest;
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

    /**
     * How far {@code subject} is along {@code quest}, as one count, the way {@link #progress(AchievementEngine,
     * Subject, Achievement)} reads an achievement: a finished one reads complete; one step counts toward its target
     * (stopping there); several count the steps done.
     */
    @Nonnull
    public static Progress progress(@Nonnull QuestEngine engine, @Nonnull Subject subject, @Nonnull Quest quest) {
        QuestStatus status = engine.status(subject, quest);
        boolean finished = status == QuestStatus.COMPLETED || status == QuestStatus.COMPLETED_UNCLAIMED
                || status == QuestStatus.ON_COOLDOWN;
        if (quest.objectives().size() == 1) {
            ObjectiveDef step = quest.objectives().get(0);
            ObjectiveProgressState state = engine.progressOf(subject, quest.id(), step.id());
            long required = Math.max(1, state == null
                    ? ObjectiveArithmetic.requiredFor(engine.objectiveKinds().kind(step.kind()), step)
                    : state.required());
            long current = state == null ? 0 : state.current();
            return new Progress(finished ? required : Math.max(0, Math.min(required, current)), required);
        }
        QuestEngine.ObjectiveTally tally = engine.tally(subject, quest);
        return new Progress(finished ? tally.total() : tally.completed(), tally.total());
    }

    /**
     * The source among {@code givers}: the first {@code visible} accepts that {@code collect} says waits only on a
     * collect, marked so; else the first {@code visible} accepts; null when none does.
     */
    @Nullable
    private static <T> Source pick(@Nonnull Kind kind, @Nonnull List<T> givers, @Nonnull Function<T, String> idOf,
            @Nonnull Predicate<T> visible, @Nonnull Function<T, Progress> progress, @Nonnull Predicate<T> collect) {
        T first = null;
        for (T giver : givers) {
            if (!visible.test(giver)) {
                continue;
            }
            if (collect.test(giver)) {
                return new Source(kind, idOf.apply(giver), progress.apply(giver), true);
            }
            if (first == null) {
                first = giver;
            }
        }
        return first == null ? null : new Source(kind, idOf.apply(first), progress.apply(first), false);
    }

    /** {@code catalogue} in {@code order}, each entry under every title {@code titles} reads off it, once per title. */
    @Nonnull
    private static <T> Map<String, List<T>> index(@Nonnull Collection<T> catalogue, @Nonnull Comparator<T> order,
            @Nonnull Function<T, List<String>> titles) {
        List<T> ordered = new ArrayList<>(catalogue.size());
        for (T entry : catalogue) {
            if (entry != null) {
                ordered.add(entry);
            }
        }
        ordered.sort(order);
        Map<String, List<T>> index = new LinkedHashMap<>();
        for (T entry : ordered) {
            for (String titleId : titles.apply(entry)) {
                List<T> list = index.computeIfAbsent(titleId, k -> new ArrayList<>());
                if (!list.contains(entry)) {
                    list.add(entry);
                }
            }
        }
        Map<String, List<T>> frozen = new LinkedHashMap<>();
        index.forEach((titleId, list) -> frozen.put(titleId, List.copyOf(list)));
        return Map.copyOf(frozen);
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
