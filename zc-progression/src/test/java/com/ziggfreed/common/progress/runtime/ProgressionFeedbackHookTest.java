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
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.QuestGates;
import com.ziggfreed.common.quest.QuestTurnInSite;
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
                .momentArg("announceKey", "yourmod.announce.first_log")
                // A fold cannot shadow a name the engine composes itself.
                .momentArg("title", "not the title")
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

        Moment progressed = last("Quest_Objective_Progressed");
        assertSame(player, progressed.subject(), "the hook is handed the subject that moved");
        assertEquals("q_parked", progressed.args().get("quest"));
        assertEquals("logs", progressed.args().get("objective"));
        assertEquals(Integer.valueOf(2), progressed.args().get("current"));
        assertEquals(Integer.valueOf(2), progressed.args().get("required"));
        assertEquals(Boolean.TRUE, progressed.args().get("finished"));

        assertEquals(2, countOf("Quest_Objective_Progressed"),
                "one moment per objective movement, no more and no fewer");

        Moment parked = only("Quest_Parked");
        assertEquals("q_parked", parked.args().get("quest"));
        assertEquals(Boolean.TRUE, parked.args().get("parked"),
                "and it says so in its arguments too, so a hook never has to read the id");
        assertEquals(QuestEngine.PARKED_COLLECT, parked.args().get("reason"),
                "a quest authored to be collected parks for that reason, so one authored file can"
                        + " word it apart from a full bag");
        assertNull(parked.args().get("turnIn"),
                "a quest collected from anywhere names no kind of place, and omits rather than"
                        + " nulls");
        Moment claimed = only("Quest_Claimed");
        assertEquals("q_parked", claimed.args().get("quest"));
        assertEquals(Boolean.TRUE, claimed.args().get("collected"),
                "collecting a parked reward says so, so a jingle for collecting can be authored"
                        + " apart from the completion of one that settled on the spot");
        assertTrue(!momentIds().contains("Quest_Completed"),
                "a quest waiting to be collected is a different moment from one that settled");
    }

    /**
     * The three ways a finished quest can park each carry their own reason token, and a quest
     * collected somewhere in particular carries the kind of place: that is what lets ONE authored
     * {@code quest.parked} file say "your bags are full", "collect it where you took it" and "come
     * back to collect it" as three cases of the same moment.
     */
    @Test
    void aParkedQuestSaysWhyItParkedAndWhereItIsCollected() {
        ProgressionRuntime.registrar(CONSUMER).feedbackHook(recorder());
        Quest fullBag = Quest.builder("q_full")
                .objective(ObjectiveDef.builder("kills", "KILL_ENTITY")
                        .target("Wolf").matchMode(MatchMode.EXACT).amount(1).build())
                .build();
        Quest atACharacter = Quest.builder("q_hand_in")
                .objective(ObjectiveDef.builder("kills", "KILL_ENTITY")
                        .target("Wolf").matchMode(MatchMode.EXACT).amount(1).build())
                .turnInAt(QuestTurnInSite.character("innkeeper"))
                .build();
        ProgressionRuntime.publishQuests(CONSUMER, List.of(fullBag, atACharacter));
        ProgressionRuntime.registrar(CONSUMER).questGates(new QuestGates() {
            @Override
            public boolean canReceiveRewards(@Nonnull Subject subject, @Nonnull Quest quest) {
                return !"q_full".equals(quest.id());
            }
        });
        ProgressionRuntime.quests().accept(player, fullBag);
        ProgressionRuntime.quests().accept(player, atACharacter);

        ProgressionRuntime.quests().dispatch(player, "KILL_ENTITY", "Wolf", null, 1);

        List<Moment> parked = new ArrayList<>();
        for (Moment moment : seen) {
            if (moment.id().equals("Quest_Parked")) {
                parked.add(moment);
            }
        }
        assertEquals(2, parked.size(), "both parked, each for its own reason: " + momentIds());
        Map<String, Moment> byQuest = new java.util.HashMap<>();
        for (Moment moment : parked) {
            byQuest.put(String.valueOf(moment.args().get("quest")), moment);
        }
        assertEquals(QuestEngine.PARKED_NO_SPACE, byQuest.get("q_full").args().get("reason"));
        assertNull(byQuest.get("q_full").args().get("turnIn"));
        assertEquals(QuestEngine.PARKED_AWAY, byQuest.get("q_hand_in").args().get("reason"),
                "finished nowhere in particular, so it waits for the character");
        assertEquals("character", byQuest.get("q_hand_in").args().get("turnIn"));
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

        Moment completed = only("Quest_Completed");
        assertEquals("q_auto", completed.args().get("quest"));
        assertEquals(Boolean.FALSE, completed.args().get("parked"));
        assertNull(completed.args().get("reason"), "a quest that paid out has no reason to park");
        assertEquals(Boolean.FALSE, only("Quest_Claimed").args().get("collected"),
                "paid on the spot is not collected: nothing waited");
        assertTrue(!momentIds().contains("Quest_Parked"));
    }

    // ==================== what the achievement engine announces ====================

    @Test
    void anAchievementAnnouncesItsUnlockCarryingItsOwnIcon() {
        ProgressionRuntime.registrar(CONSUMER).feedbackHook(recorder());
        Achievement achievement = achievement();
        ProgressionRuntime.publishAchievements(CONSUMER, List.of(achievement));

        ProgressionRuntime.achievements().dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 1);

        Moment unlocked = only("Achievement_Unlocked");
        assertSame(player, unlocked.subject());
        assertEquals("a_first_log", unlocked.args().get("achievement"));
        assertEquals("Ingredient_Bar_Iron", unlocked.args().get("icon"),
                "the icon travels with the moment, decided when the catalogue was folded");
        assertEquals(Integer.valueOf(25), unlocked.args().get("points"));
        assertEquals(Boolean.FALSE, unlocked.args().get("awaiting_claim"),
                "nothing is owed, so it is collected in the same breath");
        assertEquals("yourmod.announce.first_log", unlocked.args().get("announceKey"),
                "whatever the fold attached rides into the moment under its own name");
        assertTrue(!"not the title".equals(unlocked.args().get("title")),
                "and the engine's own names win over anything a fold attached");

        // An achievement owing nothing settles as it is earned, so the payout moment rides along
        // and carries the outcome triple whichever way it was reached.
        Moment claimed = only("Achievement_Claimed");
        assertSame(player, claimed.subject());
        assertEquals("a_first_log", claimed.args().get("achievement"));
        assertEquals("Ingredient_Bar_Iron", claimed.args().get("icon"));
        assertEquals(Boolean.FALSE, claimed.args().get("collected"),
                "settled as it was earned, not collected later");
        assertEquals("yourmod.announce.first_log", claimed.args().get("announceKey"));
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

        Moment lost = only("Achievement_Server_First_Lost");
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
                "Quest_Objective_Progressed", player, "step", (Supplier<?>) () -> {
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
                "Quest_Objective_Progressed", player, "step", (Supplier<?>) () -> {
                    composed.add("step");
                    return "Break 2 logs";
                });

        assertEquals(List.of("step"), composed);
        assertEquals("Break 2 logs", only("Quest_Objective_Progressed").args().get("step"),
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
                "Quest_Objective_Progressed", player, "step", (Supplier<?>) () -> {
                    composed.add("step");
                    return "Break 2 logs";
                });

        assertEquals(List.of("step"), composed);
        assertTrue(momentIds().contains("Quest_Objective_Progressed"));
    }

    /** A hook that cannot say is assumed to want the moment: silence is never inferred from a throw. */
    @Test
    void aHookWhoseAnswerThrowsStillGetsTheMoment() {
        ProgressionRuntime.registrar(OTHER).feedbackHook(
                ProgressionFeedbackHook.of(recorder(), momentId -> {
                    throw new IllegalStateException("cannot tell");
                }));

        ProgressionFeedbackHook.fire(ProgressionRuntime.feedback(), message -> { },
                "Quest_Objective_Progressed", player, "quest", "q_parked");

        assertTrue(momentIds().contains("Quest_Objective_Progressed"));
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

        assertTrue(momentIds().contains("Quest_Parked"),
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
        assertTrue(first.contains("Quest_Parked"));
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

        assertTrue(momentIds().contains("Quest_Parked"),
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
