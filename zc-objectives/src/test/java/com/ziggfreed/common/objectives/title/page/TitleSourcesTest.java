package com.ziggfreed.common.objectives.title.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.AchievementEngine;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.objectives.title.TitleRewardKind;
import com.ziggfreed.common.objectives.title.page.TitleSources.Kind;
import com.ziggfreed.common.progress.MatchMode;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.ObjectiveKind;
import com.ziggfreed.common.progress.ObjectiveKindRegistry;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.kit.Progress;

/**
 * Which achievements and quests give each title, read off the catalogues: one gives a title when one of its rewards,
 * paid when earned or on collect, is of the Title kind naming it. The picker's not-earned line shows the first one
 * the player can see, with how far along they are: a waiting collect first, then an achievement, then a quest.
 */
class TitleSourcesTest {

    private static final Subject ALICE = new Subject(new UUID(0, 41), "Alice", null);

    private static RewardSpec title(String id) {
        return RewardSpec.of(TitleRewardKind.KIND, "Title", id);
    }

    private static ObjectiveDef criterion(String id, long amount) {
        return ObjectiveDef.builder(id, "BREAK_BLOCK").target("Pumpkin").matchMode(MatchMode.EXACT).amount(amount)
                .build();
    }

    private static AchievementEngine engine(List<Achievement> catalogue) {
        ObjectiveKindRegistry kinds = new ObjectiveKindRegistry();
        kinds.register(null, ObjectiveKind.of("BREAK_BLOCK"));
        AchievementEngine engine = AchievementEngine.builder().objectiveKinds(kinds).nativeEvents(false).build();
        engine.setAchievements(catalogue);
        return engine;
    }

    @Test
    void anAchievementGivesEveryTitleItsRewardsNameOnUnlockOrOnCollect() {
        Achievement onUnlock = Achievement.builder("lantern_keeper").autoReward(title("Keeper_Of_Lanterns")).build();
        Achievement onCollect = Achievement.builder("pumpkin_patch").claimReward(title("Pumpkin_King"))
                .autoReward(RewardSpec.of("Money", "Amount", "5")).build();
        Achievement otherSpelling = Achievement.builder("night_watch")
                .autoReward(RewardSpec.of("title", "TitleId", "keeper_of_lanterns")).build();
        Achievement nothing = Achievement.builder("plain").autoReward(RewardSpec.of("Money", "Amount", "5")).build();

        TitleSources sources = TitleSources.of(List.of(onUnlock, onCollect, otherSpelling, nothing));

        assertEquals(List.of("lantern_keeper", "night_watch"), ids(sources.granters("Keeper_Of_Lanterns")),
                "the title id matches without regard to case, and either parameter spelling names it");
        assertEquals(List.of("pumpkin_patch"), ids(sources.granters("pumpkin_king")), "a collect reward gives it too");
        assertEquals(List.of(), ids(sources.granters("never_given")));
    }

    @Test
    void grantersListInTheCatalogueOrderAndEachOnlyOnce() {
        Achievement late = Achievement.builder("a_late").sortOrder(20).autoReward(title("Hallowed")).build();
        Achievement early = Achievement.builder("z_early").sortOrder(10).autoReward(title("Hallowed"))
                .claimReward(title("hallowed")).build();
        Achievement tie = Achievement.builder("b_tie").sortOrder(20).autoReward(title("Hallowed")).build();

        assertEquals(List.of("z_early", "a_late", "b_tie"),
                ids(TitleSources.of(List.of(late, tie, early)).granters("hallowed")),
                "sort order first, then the id; a title paid twice by one achievement lists it once");
    }

    @Test
    void aQuestGivesEveryTitleItsRewardsNameInListOrder() {
        Quest late = Quest.builder("a_late").listOrder(20).autoReward(title("Hallowed")).build();
        Quest early = Quest.builder("z_early").listOrder(10).claimRewards(List.of(title("hallowed"))).build();
        Quest tie = Quest.builder("b_tie").listOrder(20).autoReward(title("Hallowed"))
                .claimRewards(List.of(title("HALLOWED"))).build();
        Quest nothing = Quest.builder("plain").autoReward(RewardSpec.of("Money", "Amount", "5")).build();

        TitleSources sources = TitleSources.of(List.of(), List.of(late, tie, nothing, early));

        assertEquals(List.of("z_early", "a_late", "b_tie"),
                sources.questGranters("Hallowed").stream().map(Quest::id).toList(),
                "list order first, then the id; paid on the spot or on collect; a title paid twice lists it once");
        assertEquals(List.of(), sources.granters("hallowed"), "a quest is never listed as an achievement");
    }

    @Test
    void aWaitingCollectWinsThenTheAchievementThenTheQuest() {
        TitleSources.Source achievement = new TitleSources.Source(Kind.ACHIEVEMENT, "a", new Progress(1, 3), false);
        TitleSources.Source quest = new TitleSources.Source(Kind.QUEST, "q", new Progress(2, 5), false);
        TitleSources.Source questToCollect = new TitleSources.Source(Kind.QUEST, "q", new Progress(5, 5), true);
        TitleSources.Source achievementToCollect =
                new TitleSources.Source(Kind.ACHIEVEMENT, "a", new Progress(3, 3), true);

        assertEquals(achievement, TitleSources.first(achievement, quest));
        assertEquals(questToCollect, TitleSources.first(achievement, questToCollect),
                "collecting is the one step left, so it wins over an achievement still to earn");
        assertEquals(achievementToCollect, TitleSources.first(achievementToCollect, questToCollect));
        assertEquals(quest, TitleSources.first(null, quest));
        assertNull(TitleSources.first(null, null));
    }

    @Test
    void theSourceIsTheFirstGranterThePlayerCanSeeWithTheirProgress() {
        Achievement hidden = Achievement.builder("secret").sortOrder(1).hidden(true)
                .criterion(criterion("0", 3)).autoReward(title("Keeper_Of_Lanterns")).build();
        Achievement shown = Achievement.builder("lantern_keeper").sortOrder(2)
                .criterion(criterion("0", 3)).autoReward(title("Keeper_Of_Lanterns")).build();
        AchievementEngine engine = engine(List.of(hidden, shown));
        engine.store().setCriterionProgress(ALICE, "lantern_keeper", "0", 1L);
        TitleSources sources = TitleSources.of(engine.achievements());

        assertEquals(new TitleSources.Source(Kind.ACHIEVEMENT, "lantern_keeper", new Progress(1, 3), false),
                sources.source("keeper_of_lanterns", engine, ALICE),
                "a hidden achievement is never named; the next one the player can see is");
        assertNull(sources.source("never_given", engine, ALICE));
    }

    @Test
    void aTitleOnlyHiddenAchievementsGiveHasNoSource() {
        Achievement hidden = Achievement.builder("secret").hidden(true).autoReward(title("Shadow")).build();
        AchievementEngine engine = engine(List.of(hidden));

        assertNull(TitleSources.of(engine.achievements()).source("shadow", engine, ALICE));
    }

    @Test
    void progressCountsOneCriterionCappedAndAnEarnedAchievementReadsComplete() {
        Achievement single = Achievement.builder("ghouls").criterion(criterion("0", 50)).build();
        Achievement earned = Achievement.builder("earned").criterion(criterion("0", 50)).build();
        AchievementEngine engine = engine(List.of(single, earned));
        engine.store().setCriterionProgress(ALICE, "ghouls", "0", 70L);
        assertTrue(engine.unlock(ALICE, earned));

        assertEquals(new Progress(50, 50), TitleSources.progress(engine, ALICE, single), "the count stops at its target");
        assertEquals(new Progress(50, 50), TitleSources.progress(engine, ALICE, earned),
                "an earned achievement reads complete whatever its count says");
        engine.store().setCriterionProgress(ALICE, "ghouls", "0", 12L);
        assertEquals(new Progress(12, 50), TitleSources.progress(engine, ALICE, single));
    }

    @Test
    void progressCountsCriteriaMetOnSeveralAndChildrenEarnedOnACapstone() {
        Achievement several = Achievement.builder("several")
                .criteria(List.of(criterion("0", 2), criterion("1", 5), criterion("2", 1))).build();
        Achievement child = Achievement.builder("child").build();
        Achievement other = Achievement.builder("other").build();
        Achievement capstone = Achievement.builder("capstone").metaChildren(List.of("child", "other")).build();
        AchievementEngine engine = engine(List.of(several, child, other, capstone));
        engine.store().setCriterionProgress(ALICE, "several", "0", 2L);
        engine.store().setCriterionProgress(ALICE, "several", "1", 4L);
        assertTrue(engine.unlock(ALICE, child));

        assertEquals(new Progress(1, 3), TitleSources.progress(engine, ALICE, several), "criteria met, of how many");
        assertEquals(new Progress(1, 2), TitleSources.progress(engine, ALICE, capstone), "children earned, of how many");
    }

    private static List<String> ids(List<Achievement> achievements) {
        return achievements.stream().map(Achievement::id).toList();
    }
}
