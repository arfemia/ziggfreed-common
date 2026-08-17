package com.ziggfreed.common.progress.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.FirstClaims;
import com.ziggfreed.common.achievement.InMemoryAchievementProgressStore;
import com.ziggfreed.common.progress.MatchMode;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.gate.GateEvaluator;
import com.ziggfreed.common.quest.InMemoryQuestProgressStore;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.RequiresGates;
import com.ziggfreed.common.subject.Subject;

/**
 * The reaction seam: which moments the two engines announce, exactly once each, with the subject
 * that owned the transition and the values that were in scope.
 *
 * <p>The contribution properties are pinned beside them, because they are what makes a second mod
 * reacting to the same moment the normal case rather than a conflict: every registered hook fires,
 * a hook registered after the engines were built still fires, and a runtime nobody registered one
 * into is silent.
 */
class ProgressionFeedbackHookTest {

    private static final String CONSUMER = "yourmod";
    private static final String OTHER = "othermod";

    /** One moment as a hook saw it. */
    private record Moment(@Nonnull String id, @Nonnull Subject subject,
                          @Nonnull Map<String, Object> args) {
    }

    private List<Moment> seen;
    private Subject player;

    @BeforeEach
    void setUp() {
        ProgressionRuntime.resetForTests();
        FirstClaims.resetForTests();
        seen = new ArrayList<>();
        player = Subject.of(UUID.randomUUID(), "tester");
        ProgressionRuntime.registrar(CONSUMER)
                .questStore(new InMemoryQuestProgressStore())
                .achievementStore(new InMemoryAchievementProgressStore())
                .warn(message -> { });
    }

    @AfterEach
    void tearDown() {
        ProgressionRuntime.resetForTests();
        FirstClaims.resetForTests();
    }

    @Nonnull
    private ProgressionFeedbackHook recorder() {
        return (momentId, subject, args) -> seen.add(new Moment(momentId, subject, args));
    }

    @Nonnull
    private static Quest parkedQuest() {
        return Quest.builder("q_parked")
                .objective(ObjectiveDef.builder("logs", "BREAK_BLOCK")
                        .target("Oak_Log").matchMode(MatchMode.EXACT).amount(2).build())
                .autoClaim(false)
                .build();
    }

    @Nonnull
    private static Achievement achievement() {
        return Achievement.builder("a_first_log")
                .criterion(ObjectiveDef.builder("0", "BREAK_BLOCK")
                        .target("Oak_Log").matchMode(MatchMode.EXACT).amount(1).build())
                .points(25)
                .icon("Ingredient_Bar_Iron")
                .build();
    }

    @Nonnull
    private List<String> momentIds() {
        List<String> ids = new ArrayList<>();
        for (Moment moment : seen) {
            ids.add(moment.id());
        }
        return ids;
    }

    /** The LAST moment with this id, for one a single case makes happen more than once. */
    @Nonnull
    private Moment last(@Nonnull String momentId) {
        Moment found = null;
        for (Moment moment : seen) {
            if (moment.id().equals(momentId)) {
                found = moment;
            }
        }
        assertNotNull(found, momentId + " never fired, saw " + momentIds());
        return found;
    }

    @Nonnull
    private Moment only(@Nonnull String momentId) {
        Moment found = null;
        for (Moment moment : seen) {
            if (moment.id().equals(momentId)) {
                assertTrue(found == null, momentId + " fired more than once");
                found = moment;
            }
        }
        assertNotNull(found, momentId + " never fired, saw " + momentIds());
        return found;
    }

    // ==================== what the quest engine announces ====================

    @Test
    void aQuestAnnouncesEveryStepOfItsLifecycleExactlyOnce() {
        ProgressionRuntime.registrar(CONSUMER).feedbackHook(recorder());
        Quest quest = parkedQuest();
        ProgressionRuntime.publishQuests(CONSUMER, List.of(quest));
        ProgressionRuntime.quests().accept(player, quest);

        ProgressionRuntime.quests().dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 1);
        ProgressionRuntime.quests().dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 1);
        ProgressionRuntime.quests().claim(player, quest);

        Moment progressed = last("quest.objective_progressed");
        assertSame(player, progressed.subject(), "the hook is handed the subject that moved");
        assertEquals("q_parked", progressed.args().get("quest"));
        assertEquals("logs", progressed.args().get("objective"));
        assertEquals(Integer.valueOf(2), progressed.args().get("current"));
        assertEquals(Integer.valueOf(2), progressed.args().get("required"));
        assertEquals(Boolean.TRUE, progressed.args().get("finished"));

        assertEquals(2, countOf("quest.objective_progressed"),
                "one moment per objective movement, no more and no fewer");

        Moment parked = only("quest.parked");
        assertEquals("q_parked", parked.args().get("quest"));
        assertEquals(Boolean.TRUE, parked.args().get("parked"),
                "and it says so in its arguments too, so a hook never has to read the id");
        assertEquals("q_parked", only("quest.claimed").args().get("quest"));
        assertTrue(!momentIds().contains("quest.completed"),
                "a quest waiting to be collected is a different moment from one that settled");
    }

    @Test
    void aQuestThatSettlesOnTheSpotAnnouncesTheCompletedMomentInstead() {
        ProgressionRuntime.registrar(CONSUMER).feedbackHook(recorder());
        Quest quest = Quest.builder("q_auto")
                .objective(ObjectiveDef.builder("kills", "KILL_ENTITY")
                        .target("Wolf").matchMode(MatchMode.EXACT).amount(1).build())
                .build();
        ProgressionRuntime.publishQuests(CONSUMER, List.of(quest));
        ProgressionRuntime.quests().accept(player, quest);

        ProgressionRuntime.quests().dispatch(player, "KILL_ENTITY", "Wolf", null, 1);

        Moment completed = only("quest.completed");
        assertEquals("q_auto", completed.args().get("quest"));
        assertEquals(Boolean.FALSE, completed.args().get("parked"));
        assertTrue(!momentIds().contains("quest.parked"));
    }

    // ==================== what the achievement engine announces ====================

    @Test
    void anAchievementAnnouncesItsUnlockCarryingItsOwnIcon() {
        ProgressionRuntime.registrar(CONSUMER).feedbackHook(recorder());
        Achievement achievement = achievement();
        ProgressionRuntime.publishAchievements(CONSUMER, List.of(achievement));

        ProgressionRuntime.achievements().dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 1);

        Moment unlocked = only("achievement.unlocked");
        assertSame(player, unlocked.subject());
        assertEquals("a_first_log", unlocked.args().get("achievement"));
        assertEquals("Ingredient_Bar_Iron", unlocked.args().get("icon"),
                "the icon travels with the moment, decided when the catalogue was folded");
        assertEquals(Integer.valueOf(25), unlocked.args().get("points"));
        assertEquals(Boolean.FALSE, unlocked.args().get("awaiting_claim"),
                "nothing is owed, so it is collected in the same breath");

        // An achievement owing nothing settles as it is earned, so the payout moment rides along
        // and carries the outcome triple whichever way it was reached.
        Moment claimed = only("achievement.claimed");
        assertSame(player, claimed.subject());
        assertEquals("a_first_log", claimed.args().get("achievement"));
        assertEquals("Ingredient_Bar_Iron", claimed.args().get("icon"));
        assertEquals(Integer.valueOf(0), claimed.args().get("granted"));
        assertEquals(Integer.valueOf(0), claimed.args().get("queued"));
        assertEquals(Integer.valueOf(0), claimed.args().get("failed"));
    }

    /**
     * Losing a one-winner race announces itself too, from the CLAIM TABLE rather than an engine: the
     * refusal an engine sees carries no reason, and the table is the one place that knows a race was
     * lost however the decision was reached.
     */
    @Test
    void losingAServerFirstRaceAnnouncesItsOwnMoment() {
        ProgressionRuntime.registrar(CONSUMER).feedbackHook(recorder());
        RequiresGates gates = RequiresGates.of(GateEvaluator.builder().build());
        Achievement onlyOnce = Achievement.builder("a_first_blood")
                .serverFirst(true)
                .icon("Weapon_Longsword_Iron")
                .build();

        gates.canUnlock(Subject.of(UUID.randomUUID(), "winner"), onlyOnce);
        gates.canUnlock(player, onlyOnce);

        Moment lost = only("achievement.server_first_lost");
        assertSame(player, lost.subject(), "the moment is about whoever was beaten");
        assertEquals("a_first_blood", lost.args().get("achievement"));
        assertNotNull(lost.args().get("title"), "a moment with nothing to name reads as nothing");
        assertNull(lost.args().get("icon"),
                "and it carries no picture: a loss is a quiet note, not a second unlock");
    }

    // ==================== what a moment costs when nobody answers it ====================

    /**
     * A moment announced on a hot path may carry a value that is EXPENSIVE to compose, and it does
     * so as a supplier. A hook that can prove it has nothing to do with this moment is taken at its
     * word and the supplier is never asked, which is the whole reason a lifecycle moment can be
     * announced on every objective tick.
     */
    @Test
    void aDeferredArgumentIsNotComposedForAMomentNobodyAnswers() {
        List<String> composed = new ArrayList<>();
        ProgressionRuntime.registrar(CONSUMER).feedbackHook(
                ProgressionFeedbackHook.of(recorder(), momentId -> false));

        ProgressionFeedbackHook.fire(ProgressionRuntime.feedback(), message -> { },
                "quest.objective_progressed", player, "step", (Supplier<?>) () -> {
                    composed.add("step");
                    return "Break 2 logs";
                });

        assertEquals(List.of(), composed, "nothing was composed for a moment nobody answers");
        assertTrue(seen.isEmpty(), "and nothing was announced either");
    }

    /** And when somebody DOES answer it, the value is composed once and handed over plainly. */
    @Test
    void aDeferredArgumentIsComposedOnceForAMomentSomebodyAnswers() {
        List<String> composed = new ArrayList<>();
        ProgressionRuntime.registrar(CONSUMER).feedbackHook(recorder());

        ProgressionFeedbackHook.fire(ProgressionRuntime.feedback(), message -> { },
                "quest.objective_progressed", player, "step", (Supplier<?>) () -> {
                    composed.add("step");
                    return "Break 2 logs";
                });

        assertEquals(List.of("step"), composed);
        assertEquals("Break 2 logs", only("quest.objective_progressed").args().get("step"),
                "a reader is handed the composed value, never the supplier that made it");
    }

    /**
     * One hook that answers is enough for the whole fan-out, because a moment is announced once and
     * every hook is handed the same arguments.
     */
    @Test
    void oneHookAnsweringIsEnoughForTheWholeFanOut() {
        List<String> composed = new ArrayList<>();
        ProgressionRuntime.registrar(OTHER).feedbackHook(
                ProgressionFeedbackHook.of((momentId, subject, args) -> { }, momentId -> false));
        ProgressionRuntime.registrar(CONSUMER).feedbackHook(recorder());

        ProgressionFeedbackHook.fire(ProgressionRuntime.feedback(), message -> { },
                "quest.objective_progressed", player, "step", (Supplier<?>) () -> {
                    composed.add("step");
                    return "Break 2 logs";
                });

        assertEquals(List.of("step"), composed);
        assertTrue(momentIds().contains("quest.objective_progressed"));
    }

    /** A hook that cannot say is assumed to want the moment: silence is never inferred from a throw. */
    @Test
    void aHookWhoseAnswerThrowsStillGetsTheMoment() {
        ProgressionRuntime.registrar(OTHER).feedbackHook(
                ProgressionFeedbackHook.of(recorder(), momentId -> {
                    throw new IllegalStateException("cannot tell");
                }));

        ProgressionFeedbackHook.fire(ProgressionRuntime.feedback(), message -> { },
                "quest.objective_progressed", player, "quest", "q_parked");

        assertTrue(momentIds().contains("quest.objective_progressed"));
    }

    // ==================== the contribution properties ====================

    @Test
    void aRuntimeNobodyRegisteredAHookIntoIsSilent() {
        Quest quest = parkedQuest();
        ProgressionRuntime.publishQuests(CONSUMER, List.of(quest));
        ProgressionRuntime.quests().accept(player, quest);
        ProgressionRuntime.quests().dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 2);

        assertTrue(seen.isEmpty(), "an unfilled seam does nothing rather than failing");
    }

    @Test
    void aHookRegisteredAfterTheEnginesWereBuiltStillFires() {
        Quest quest = parkedQuest();
        ProgressionRuntime.publishQuests(CONSUMER, List.of(quest));
        // Reading the engine builds the runtime, so this registration is genuinely late.
        ProgressionRuntime.quests().accept(player, quest);
        assertTrue(ProgressionRuntime.isBuilt());

        ProgressionRuntime.registrar(CONSUMER).feedbackHook(recorder());
        ProgressionRuntime.quests().dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 2);

        assertTrue(momentIds().contains("quest.parked"),
                "the engines call through a live forwarder, so a late hook is honoured");
    }

    @Test
    void everyRegisteredHookSeesEveryMomentWhicheverOrderTheyArrivedIn() {
        List<String> first = new ArrayList<>();
        List<String> second = new ArrayList<>();
        ProgressionRuntime.registrar(CONSUMER)
                .feedbackHook((momentId, subject, args) -> first.add(momentId));
        ProgressionRuntime.registrar(OTHER)
                .feedbackHook((momentId, subject, args) -> second.add(momentId));

        Quest quest = parkedQuest();
        ProgressionRuntime.publishQuests(CONSUMER, List.of(quest));
        ProgressionRuntime.quests().accept(player, quest);
        ProgressionRuntime.quests().dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 2);

        assertEquals(first, second, "nothing can mark a moment as already handled");
        assertTrue(first.contains("quest.parked"));
        assertEquals(List.of(CONSUMER, OTHER), ProgressionRuntime.feedbackHookOwners());
    }

    @Test
    void aThrowingHookCostsItsOwnReactionAndNobodyElses() {
        ProgressionRuntime.registrar(OTHER).feedbackHook((momentId, subject, args) -> {
            throw new IllegalStateException("broken reaction");
        });
        ProgressionRuntime.registrar(CONSUMER).feedbackHook(recorder());

        Quest quest = parkedQuest();
        ProgressionRuntime.publishQuests(CONSUMER, List.of(quest));
        ProgressionRuntime.quests().accept(player, quest);
        ProgressionRuntime.quests().dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 2);

        assertTrue(momentIds().contains("quest.parked"),
                "the second hook still ran, and the quest still completed");
    }

    private int countOf(@Nonnull String momentId) {
        int count = 0;
        for (Moment moment : seen) {
            if (moment.id().equals(momentId)) {
                count++;
            }
        }
        return count;
    }
}
