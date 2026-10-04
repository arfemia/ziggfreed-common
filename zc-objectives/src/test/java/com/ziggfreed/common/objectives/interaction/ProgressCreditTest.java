package com.ziggfreed.common.objectives.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.AchievementEngine;
import com.ziggfreed.common.achievement.InMemoryAchievementProgressStore;
import com.ziggfreed.common.progress.MatchMode;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.ObjectiveKindRegistry;
import com.ziggfreed.common.quest.InMemoryQuestProgressStore;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.subject.Subject;

/**
 * What one ZigCreditProgress node credits, and what that credit advances once both engines hear
 * it. The engines run over in-memory stores with their own seeded vocabulary, so a credit reaching
 * a USE_ITEM step here proves the kind is seeded as well as the resolution.
 */
class ProgressCreditTest {

    private static final String BOMB = "Weapon_Bomb";

    private Subject player;
    private QuestEngine quests;
    private AchievementEngine achievements;

    @BeforeEach
    void setUp() {
        player = Subject.of(UUID.randomUUID(), "tester");
        quests = QuestEngine.builder()
                .store(new InMemoryQuestProgressStore())
                .nativeEvents(false)
                .warn(message -> { })
                .build();
        achievements = AchievementEngine.builder()
                .store(new InMemoryAchievementProgressStore())
                .nativeEvents(false)
                .warn(message -> { })
                .build();
    }

    @Nonnull
    private static ObjectiveDef useStep(@Nonnull String id, @Nullable String qualifier, long amount) {
        return ObjectiveDef.builder(id, ProgressCredit.DEFAULT_KIND)
                .target(BOMB).matchMode(MatchMode.EXACT).qualifier(qualifier).amount(amount).build();
    }

    /** Hand a credit to both engines the way the Type hands it to ProgressDispatch. */
    private void credit(@Nonnull ProgressCredit credit) {
        assertTrue(ProgressCredit.creditable(new ObjectiveKindRegistry(), credit.kind()),
                credit.kind() + " must be creditable for the Type to fire it");
        quests.dispatch(player, credit.kind(), credit.target(), credit.qualifier(), credit.amount());
        achievements.dispatch(player, credit.kind(), credit.target(), credit.qualifier(), credit.amount());
    }

    @Test
    void aNodeAuthoringNothingCreditsOneUseOfTheItemItIsAbout() {
        assertEquals(new ProgressCredit("USE_ITEM", BOMB, null, 1L),
                ProgressCredit.resolve(null, null, null, null, BOMB));
    }

    @Test
    void everyAuthoredLeafWinsOverItsDefault() {
        assertEquals(new ProgressCredit("RING_BELL", "Bell_Tower", "Dusk", 3L),
                ProgressCredit.resolve("ring_bell", "Bell_Tower", "Dusk", 3, BOMB));
    }

    @Test
    void aBlankLeafReadsAsUnauthored() {
        assertEquals(new ProgressCredit("USE_ITEM", BOMB, null, 1L),
                ProgressCredit.resolve("  ", " ", "", null, " " + BOMB + " "));
    }

    @Test
    void aUseThatCanNameNoItemCreditsNothing() {
        assertNull(ProgressCredit.resolve(null, null, "Throw", null, null));
        assertNull(ProgressCredit.resolve(null, "  ", "Throw", null, "  "));
    }

    @Test
    void anAmountBelowOneCreditsNothingRatherThanTakingProgressAway() {
        assertNull(ProgressCredit.resolve(null, null, "Throw", 0, BOMB));
        assertNull(ProgressCredit.resolve(null, null, "Throw", -2, BOMB));
    }

    @Test
    void aThrowCountsForAThrowStepAndAnUnqualifiedStepButNotACrackStep() {
        Achievement throwing = Achievement.builder("a_throw").criterion(useStep("0", "Throw", 5)).build();
        Achievement cracking = Achievement.builder("a_crack").criterion(useStep("0", "Crack", 5)).build();
        Achievement anyUse = Achievement.builder("a_any").criterion(useStep("0", null, 5)).build();
        achievements.setAchievements(List.of(throwing, cracking, anyUse));
        Quest quest = Quest.builder("q_bombs").objective(useStep("bombs", "Throw", 2)).build();
        quests.setQuests(List.of(quest));
        assertTrue(quests.accept(player, quest));

        ProgressCredit credit = ProgressCredit.resolve(null, null, "Throw", null, BOMB);
        assertNotNull(credit);
        credit(credit);

        assertEquals(1, achievements.progressOf(player, throwing, 0).current());
        assertEquals(0, achievements.progressOf(player, cracking, 0).current(),
                "a crack step counts cracks only");
        assertEquals(1, achievements.progressOf(player, anyUse, 0).current(),
                "a step with no qualifier counts every use");
        assertNotNull(quests.progressOf(player, "q_bombs", "bombs"));
        assertEquals(1, quests.progressOf(player, "q_bombs", "bombs").current());
    }

    @Test
    void theQualifierMatchesWhateverCaseEitherSideWroteIt() {
        Achievement throwing = Achievement.builder("a_throw").criterion(useStep("0", "Throw", 5)).build();
        achievements.setAchievements(List.of(throwing));

        ProgressCredit credit = ProgressCredit.resolve(null, null, "THROW", null, BOMB);
        assertNotNull(credit);
        credit(credit);

        assertEquals(1, achievements.progressOf(player, throwing, 0).current());
    }

    @Test
    void aKindNoStepCanCountTakesNoCredit() {
        ObjectiveKindRegistry kinds = new ObjectiveKindRegistry();

        assertNull(ProgressCredit.refusal(kinds, "USE_ITEM"), "the seeded use kind takes a credit");
        assertNull(ProgressCredit.refusal(kinds, "consume_item"), "any accumulating kind takes one");
        assertNotNull(ProgressCredit.refusal(kinds, "Use_Itme"), "a typo is no kind at all");
        assertNotNull(ProgressCredit.refusal(kinds, ObjectiveKindRegistry.STAT_THRESHOLD),
                "a standing value is never a number of uses");
        assertFalse(ProgressCredit.creditable(kinds, ObjectiveKindRegistry.STAT_THRESHOLD));
        assertTrue(ProgressCredit.creditable(kinds, "USE_ITEM"));
    }

    @Test
    void aKindIsReportedOncePerProcessInAnyCasing() {
        Set<String> reported = new HashSet<>();

        assertTrue(ProgressCredit.firstReport(reported, "Use_Itme"));
        assertFalse(ProgressCredit.firstReport(reported, "USE_ITME"), "the same kind, said once");
    }
}
