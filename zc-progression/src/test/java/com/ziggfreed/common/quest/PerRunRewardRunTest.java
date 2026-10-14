package com.ziggfreed.common.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.factor.FeatureFlags;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.occurrence.Occurrences;
import com.ziggfreed.common.occurrence.RunLabels;
import com.ziggfreed.common.progress.MatchMode;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.season.SeasonGate;
import com.ziggfreed.common.subject.Subject;

/**
 * The run sentinels in a quest's reward rows: a quest that comes round once a run of a calendar event pays
 * {@code @run}, written as a parameter's whole value, as the label of the run it was finished in (its year unless a
 * kind's labeler names it), and {@code @year} as that run's year, even when the player collects it after that run is
 * over; the page shows the row the collect pays. A quest with no such rule, and a value that only contains a
 * sentinel, keep it as written.
 */
class PerRunRewardRunTest {

    private static final String EVENT = "Spring_Fair";

    /** A keepsake named for its run, beside its year and a parameter that only mentions a sentinel. */
    private static final RewardSpec KEEPSAKE = RewardSpec.of("NOTE", Map.of(
            "Item", "Fixture_Lantern",
            "StackNameKey", "yourmod.keepsake.name",
            "StackNameArg", "@run",
            "Year", "@year",
            "Label", "the @run fair"));

    private InMemoryQuestProgressStore store;
    private AtomicLong clock;
    private Subject player;
    private FakeRuns runs;
    private List<RewardSpec> granted;

    @BeforeEach
    void setUp() {
        store = new InMemoryQuestProgressStore();
        clock = new AtomicLong();
        player = Subject.of(UUID.randomUUID(), "tester");
        granted = new ArrayList<>();
        runs = new FakeRuns(EVENT);
        Occurrences.fill(runs);
        FeatureFlags.register("ziggfreedcommon", SeasonGate.featureOf(EVENT), "test",
                () -> runs.live(EVENT, clock.get()) != null);
    }

    @AfterEach
    void tearDown() {
        Occurrences.resetForTests();
        FeatureFlags.reset();
        RunLabels.resetForTests();
    }

    @Nonnull
    private QuestEngine engine(@Nonnull Quest quest) {
        RewardKindRegistry kinds = new RewardKindRegistry();
        kinds.register("NOTE", (spec, subject) -> granted.add(spec));
        QuestEngine engine = QuestEngine.builder()
                .store(store)
                .rewardKinds(kinds)
                .clock(clock::get)
                .nativeEvents(false)
                .warn(message -> { })
                .build();
        engine.setQuests(List.of(quest));
        return engine;
    }

    @Nonnull
    private static Quest.Builder oneLog(@Nonnull String id) {
        return Quest.builder(id).objective(ObjectiveDef.builder("logs", "BREAK_BLOCK")
                .target("Oak_Log").matchMode(MatchMode.EXACT).amount(1).build());
    }

    /** A once-a-run quest whose keepsake waits to be collected. */
    @Nonnull
    private static Quest keepsakeQuest() {
        return oneLog("q_keepsake")
                .repeat(new Quest.Repeat(0L, Quest.Repeat.CooldownFrom.CLAIM, null, 0,
                        new Quest.Repeat.PerRun(EVENT, 1)))
                .reward(KEEPSAKE)
                .build();
    }

    /** Take {@code quest} at {@code iso} and break the one log it asks for. */
    private void finishAt(@Nonnull QuestEngine engine, @Nonnull Quest quest, @Nonnull String iso) {
        clock.set(FakeRuns.at(iso));
        engine.selfHeal(player);
        assertTrue(engine.accept(player, quest), "the quest is taken at " + iso);
        engine.dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 1);
    }

    /** Collect the waiting keepsake at {@code iso} and answer the one row it paid. */
    @Nonnull
    private RewardSpec collectAt(@Nonnull QuestEngine engine, @Nonnull Quest quest, @Nonnull String iso) {
        clock.set(FakeRuns.at(iso));
        assertNotNull(engine.tryClaim(player, quest), "collected at " + iso);
        assertEquals(1, granted.size());
        return granted.get(0);
    }

    @Test
    void aOnceARunQuestPaysForTheRunItWasFinishedIn() {
        runs.run(2026, "2026-10-01", "2026-11-03").run(2027, "2027-10-01", "2027-11-03");
        Quest quest = keepsakeQuest();
        QuestEngine engine = engine(quest);

        finishAt(engine, quest, "2026-10-10T12:00:00Z");
        assertEquals(QuestStatus.COMPLETED_UNCLAIMED, store.status(player, quest.id()), "the keepsake waits");
        assertEquals("2026", engine.rewardsFor(player, quest, quest.claimRewards()).get(0).param("StackNameArg"),
                "the page shows the row the collect will pay");

        RewardSpec paid = collectAt(engine, quest, "2027-01-15T12:00:00Z");
        assertEquals("2026", paid.param("StackNameArg"),
                "the run it was finished in, labelled by its year, not the moment it was collected");
        assertEquals("2026", paid.param("Year"), "@year reads that run's year");
        assertEquals("the @run fair", paid.param("Label"), "only a whole value is a sentinel");
        assertEquals("Fixture_Lantern", paid.param("Item"), "every other parameter stays as written");
    }

    @Test
    void anEventKindsLabelerNamesTheRun() {
        RunLabels.register("yourmod", (eventId, year, number) -> "run " + number + " of " + year);
        runs.run(2026, "2026-10-01", "2026-11-03");
        Quest quest = keepsakeQuest();
        QuestEngine engine = engine(quest);

        finishAt(engine, quest, "2026-10-10T12:00:00Z");
        RewardSpec paid = collectAt(engine, quest, "2026-10-11T12:00:00Z");
        assertEquals("run 1 of 2026", paid.param("StackNameArg"), "@run reads the label the labeler gives");
        assertEquals("2026", paid.param("Year"), "@year still reads the run's year");
    }

    @Test
    void aQuestWithNoRunRuleLeavesTheSentinelsAsWritten() {
        runs.run(2026, "2026-10-01", "2026-11-03");
        Quest quest = oneLog("q_plain").autoReward(KEEPSAKE).build();
        QuestEngine engine = engine(quest);

        finishAt(engine, quest, "2026-10-10T12:00:00Z");
        assertEquals(QuestStatus.COMPLETED, store.status(player, quest.id()), "it settled on the spot");
        assertEquals(1, granted.size());
        assertEquals("@run", granted.get(0).param("StackNameArg"), "no calendar event owns its run");
        assertEquals("@year", granted.get(0).param("Year"));
        assertEquals("@run", engine.rewardsFor(player, quest, quest.autoRewards()).get(0).param("StackNameArg"));
    }
}
