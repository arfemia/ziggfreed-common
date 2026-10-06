package com.ziggfreed.common.objectives.producer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.event.IEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.AchievementEngine;
import com.ziggfreed.common.achievement.InMemoryAchievementProgressStore;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.progress.MatchMode;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.ObjectiveKindRegistry;
import com.ziggfreed.common.progress.runtime.Moment;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.quest.InMemoryQuestProgressStore;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.quest.event.QuestClaimedEvent;
import com.ziggfreed.common.quest.event.QuestCompletedEvent;
import com.ziggfreed.common.quest.event.QuestEvents;
import com.ziggfreed.common.subject.Subject;

/**
 * A finished quest is ONE {@code COMPLETE_QUEST} moment naming it, counted when its reward is
 * collected: on the spot for a quest that pays out as its last step lands, at the collect for one
 * that parks. That is when a finished quest reads as finished everywhere else (a prerequisite, the
 * {@code quest_completed} reading, the auto-accepts it arms), and it is when a consumer that used to
 * produce this moment itself counted it.
 *
 * <p>Driven over a REAL quest engine with its native events on, observed through the
 * {@link QuestEvents.Publisher} seam the way {@code QuestTrackedEventTest} observes the pin event:
 * there is no engine bus in a unit JVM. The stand-in bus hands each claim to the producer's own
 * fan-out, exactly as the one registration {@code install} makes does, and every completion event is
 * recorded beside it, so the test can show a settled quest firing BOTH native events while only
 * one moment comes out.
 */
class ZigQuestCompletionProducerTest {

    private static final long HOUR = 3_600_000L;
    private static final long DAY = 24 * HOUR;

    /** One produced moment as the sink sees it, flattened so a list of them compares whole. */
    private record Produced(@Nonnull UUID playerId, @Nonnull String kind, @Nonnull String target,
            @Nullable String qualifier, long amount) {
    }

    private final List<Produced> produced = new ArrayList<>();
    private final List<QuestCompletedEvent> completions = new ArrayList<>();
    private final List<QuestClaimedEvent> claims = new ArrayList<>();
    private final Ref<EntityStore> playerRef = new Ref<EntityStore>((Store<EntityStore>) null);

    private Subject player;
    private QuestEngine quests;
    private AchievementEngine achievements;

    @BeforeEach
    void setUp() {
        ProgressionRuntime.resetForTests();
        player = Subject.of(UUID.randomUUID(), "finisher");
        RewardKindRegistry rewardKinds = new RewardKindRegistry();
        rewardKinds.register("NOTE", (spec, subject) -> { });
        quests = QuestEngine.builder()
                .store(new InMemoryQuestProgressStore())
                .rewardKinds(rewardKinds)
                .nativeEvents(true)
                .warn(message -> { })
                .build();
        achievements = AchievementEngine.builder()
                .store(new InMemoryAchievementProgressStore())
                .nativeEvents(false)
                .warn(message -> { })
                .build();
        QuestEvents.publishTo(new QuestEvents.Publisher() {
            @Override
            public <E extends IEvent<Void>> void publish(@Nonnull Class<E> type, @Nonnull Supplier<E> build) {
                if (type == QuestCompletedEvent.class) {
                    completions.add((QuestCompletedEvent) build.get());
                } else if (type == QuestClaimedEvent.class) {
                    QuestClaimedEvent claim = (QuestClaimedEvent) build.get();
                    claims.add(claim);
                    ZigQuestCompletionProducer.fanOut(claim,
                            questId -> ZigQuestCompletionProducer.qualifierOf(questId, quests.quest(questId)),
                            (playerId, kind, target, qualifier, amount) ->
                                    produced.add(new Produced(playerId, kind, target, qualifier, amount)));
                }
            }
        });
    }

    @AfterEach
    void tearDown() {
        QuestEvents.publishTo(null);
        ProgressionRuntime.resetForTests();
    }

    @Nonnull
    private static ObjectiveDef chop(long amount) {
        return ObjectiveDef.builder("logs", "BREAK_BLOCK").target("Oak_Log").matchMode(MatchMode.EXACT)
                .amount(amount).build();
    }

    @Nonnull
    private static ObjectiveDef finish(@Nonnull String id, @Nullable String questId, @Nullable String qualifier,
            long amount) {
        return ObjectiveDef.builder(id, ZigQuestCompletionProducer.KIND).target(questId)
                .matchMode(MatchMode.EXACT).qualifier(qualifier).amount(amount).build();
    }

    /** Accept and finish one quest whose only step is a single log. */
    private void acceptAndFinish(@Nonnull Quest quest) {
        assertTrue(quests.accept(player, quest));
        quests.dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 1L);
    }

    /** The moment a produced row stands for, as the dispatch hands it to every reaction and both engines. */
    @Nonnull
    private Moment momentOf(@Nonnull Produced row) {
        return new Moment(row.kind(), row.target(), row.qualifier(), row.amount(), null,
                (Store<EntityStore>) null, playerRef, null, player, player, null);
    }

    @Test
    void theMomentIsTheBuiltInProducibleKind() {
        assertTrue(ObjectiveKindRegistry.isBuiltIn(ZigQuestCompletionProducer.KIND),
                "content already authors this kind; the producer is what finally fires it");
        assertTrue(new ObjectiveKindRegistry().isProducible(ZigQuestCompletionProducer.KIND),
                "and a step of it may be authored, so a quest can ask for another to be finished");
    }

    @Test
    void aQuestThatPaysOutOnTheSpotIsOneMomentNamingIt() {
        Quest quest = Quest.builder("q_chop").objective(chop(1)).build();
        quests.setQuests(List.of(quest));

        acceptAndFinish(quest);

        assertEquals(QuestStatus.COMPLETED, quests.status(player, quest));
        assertEquals(1, completions.size(), "the engine announced the completion");
        assertFalse(completions.get(0).parked());
        assertEquals(1, claims.size(), "and the payout, right behind it");
        assertEquals(List.of(new Produced(player.id(), "COMPLETE_QUEST", "q_chop", "NORMAL", 1L)), produced,
                "two native events, ONE moment: target the quest id, a one-shot's qualifier, amount 1");
    }

    @Test
    void aParkedQuestCountsWhenItIsCollectedAndNotWhenItParks() {
        Quest quest = Quest.builder("q_parcel").objective(chop(1))
                .reward(RewardSpec.of("NOTE", "text", "collected")).build();
        quests.setQuests(List.of(quest));

        acceptAndFinish(quest);

        assertEquals(QuestStatus.COMPLETED_UNCLAIMED, quests.status(player, quest));
        assertEquals(1, completions.size());
        assertTrue(completions.get(0).parked(), "the steps are done and the reward waits");
        assertTrue(produced.isEmpty(), "nothing is counted while the reward is still waiting to be collected");

        assertTrue(quests.claim(player, quest));
        assertEquals(List.of(new Produced(player.id(), "COMPLETE_QUEST", "q_parcel", "NORMAL", 1L)), produced,
                "the collect is the one moment the quest counts");

        assertFalse(quests.claim(player, quest), "a second collect is refused");
        assertEquals(1, produced.size(), "and counts nothing");
    }

    @Test
    void theQualifierIsHowOftenTheQuestComesRound() {
        Quest once = Quest.builder("q_once").objective(chop(1)).build();
        Quest often = Quest.builder("q_often").objective(chop(1)).repeat(Quest.Repeat.every(2 * HOUR)).build();
        Quest daily = Quest.builder("q_daily").objective(chop(1)).repeat(Quest.Repeat.every(DAY)).build();
        Quest weekly = Quest.builder("q_weekly").objective(chop(1)).repeat(Quest.Repeat.every(7 * DAY)).build();

        assertEquals("NORMAL", ZigQuestCompletionProducer.qualifierOf("q_once", once));
        assertEquals("REPEATABLE", ZigQuestCompletionProducer.qualifierOf("q_often", often));
        assertEquals("DAILY", ZigQuestCompletionProducer.qualifierOf("q_daily", daily));
        assertEquals("WEEKLY", ZigQuestCompletionProducer.qualifierOf("q_weekly", weekly));
        assertNull(ZigQuestCompletionProducer.qualifierOf("q_gone", null),
                "a quest the runtime no longer holds has no cadence to read, so its moment goes unqualified");
    }

    @Test
    void aRegisteredAnswerOutranksTheQuestsOwnCadence() {
        // A contract a board posts every day holds no clock of its own: its quest reads repeatable,
        // and only the owner of the board knows it comes round daily.
        Quest contract = Quest.builder("q_contract").objective(chop(1))
                .repeat(Quest.Repeat.EXTERNALLY_GOVERNED).build();
        Quest once = Quest.builder("q_once").objective(chop(1)).build();
        ProgressionRuntime.registrar("yourmod")
                .questCompletionQualifier(questId -> "q_contract".equals(questId) ? "DAILY" : null);

        assertTrue(ProgressionRuntime.questCompletionQualifierOwners().contains("yourmod"));
        assertEquals("DAILY", ZigQuestCompletionProducer.qualifierOf("q_contract", contract),
                "the registered answer is stamped instead of the quest's own repeatable");
        assertEquals("NORMAL", ZigQuestCompletionProducer.qualifierOf("q_once", once),
                "and a quest it does not answer for keeps its own cadence");
    }

    @Test
    void aThrowingAnswerIsSkippedAndTheNextIsAsked() {
        List<String> warnings = new ArrayList<>();
        ProgressionRuntime.registrar("brokenmod")
                .warn(warnings::add)
                .questCompletionQualifier(questId -> {
                    throw new IllegalStateException("boom");
                });
        ProgressionRuntime.registrar("yourmod").questCompletionQualifier(questId -> "WEEKLY");

        assertEquals("WEEKLY", ProgressionRuntime.questCompletionQualifier().qualifierFor("q_any"));
        assertEquals(1, warnings.size(), "the broken one is reported once per ask");
    }

    /**
     * The one moment, dispatched as the producer dispatches it, advances every criterion that wants
     * it exactly once: one counting any quest, one counting dailies, one naming this quest, and a step
     * on another quest that asks for this one to be finished. A criterion counting weeklies is left
     * alone.
     */
    @Test
    void theOneMomentAdvancesEachCompleteQuestCriterionOnce() {
        Quest chores = Quest.builder("q_chores").objective(chop(1)).repeat(Quest.Repeat.every(DAY)).build();
        Quest after = Quest.builder("q_after").objective(finish("chores", "q_chores", null, 2)).build();
        quests.setQuests(List.of(chores, after));
        Achievement any = Achievement.builder("a_any").criterion(finish("0", null, null, 5)).build();
        Achievement dailies = Achievement.builder("a_dailies").criterion(finish("0", null, "DAILY", 5)).build();
        Achievement weeklies = Achievement.builder("a_weeklies").criterion(finish("0", null, "WEEKLY", 5)).build();
        Achievement named = Achievement.builder("a_named").criterion(finish("0", "q_chores", null, 5)).build();
        achievements.setAchievements(List.of(any, dailies, weeklies, named));
        assertTrue(quests.accept(player, after));

        acceptAndFinish(chores);
        assertEquals(1, produced.size());
        ProgressDispatch.produce(quests, achievements, momentOf(produced.get(0)));

        assertEquals(1, achievements.progressOf(player, any, 0).current(), "any finished quest counts once");
        assertEquals(1, achievements.progressOf(player, dailies, 0).current(), "a daily counts as a daily, once");
        assertEquals(0, achievements.progressOf(player, weeklies, 0).current(), "and never as a weekly");
        assertEquals(1, achievements.progressOf(player, named, 0).current(), "the criterion naming it, once");
        assertEquals(1, quests.progressOf(player, "q_after", "chores").current(),
                "and a step on another quest asking for it to be finished, once");
        assertEquals(1, produced.size(), "the step on q_after is not done, so nothing else was produced");
    }
}
