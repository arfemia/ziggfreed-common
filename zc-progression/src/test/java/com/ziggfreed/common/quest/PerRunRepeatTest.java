package com.ziggfreed.common.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.factor.FeatureFlags;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.occurrence.Occurrences;
import com.ziggfreed.common.progress.MatchMode;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.season.SeasonGate;
import com.ziggfreed.common.subject.Subject;

/**
 * Once a run of a calendar event: a quest finished in a run is not offered again in that run, whatever
 * moves the run (a force, an owner moving its days, the new year inside it); an old record with no run
 * year counts for the run its last finish fell in; and an unfinished quest keeps its progress into the
 * next run, where finishing it counts. Runs are keyed (event, year), never by whether now is in them.
 */
class PerRunRepeatTest {

    private static final String EVENT = "Spring_Fair";

    private InMemoryQuestProgressStore store;
    private AtomicLong clock;
    private Subject player;
    private FakeRuns runs;

    @BeforeEach
    void setUp() {
        store = new InMemoryQuestProgressStore();
        clock = new AtomicLong();
        player = Subject.of(UUID.randomUUID(), "tester");
        runs = new FakeRuns(EVENT);
        Occurrences.fill(runs);
        // The event's running switch, as the calendar declares it; Quest.available() reads it through SeasonGate.
        FeatureFlags.register("ziggfreedcommon", SeasonGate.featureOf(EVENT), "test",
                () -> runs.live(EVENT, clock.get()) != null);
    }

    @AfterEach
    void tearDown() {
        Occurrences.resetForTests();
        FeatureFlags.reset();
    }

    @Nonnull
    private QuestEngine engine(@Nonnull Quest quest) {
        QuestEngine engine = QuestEngine.builder()
                .store(store)
                .rewardKinds(new RewardKindRegistry())
                .clock(clock::get)
                .nativeEvents(false)
                .warn(message -> { })
                .build();
        engine.setQuests(List.of(quest));
        return engine;
    }

    /** One step of {@code logs} logs, no reward to collect (it settles on the spot), {@code times} a run, capped at {@code max}. */
    @Nonnull
    private static Quest onceARun(int times, int logs, int max) {
        return Quest.builder("q_lantern")
                .objective(ObjectiveDef.builder("logs", "BREAK_BLOCK")
                        .target("Oak_Log").matchMode(MatchMode.EXACT).amount(logs).build())
                .repeat(new Quest.Repeat(0L, Quest.Repeat.CooldownFrom.CLAIM, null, max,
                        new Quest.Repeat.PerRun(EVENT, times)))
                .build();
    }

    /** Is the quest offered at {@code iso}, after the maintenance a login runs? */
    private boolean offeredAt(@Nonnull QuestEngine engine, @Nonnull Quest quest, @Nonnull String iso) {
        clock.set(FakeRuns.at(iso));
        engine.selfHeal(player);
        return engine.canAccept(player, quest).allowed();
    }

    /** Take the one-log quest at {@code iso} and finish it. */
    private void finishAt(@Nonnull QuestEngine engine, @Nonnull Quest quest, @Nonnull String iso) {
        assertTrue(offeredAt(engine, quest, iso), "the quest is offered at " + iso);
        assertTrue(engine.accept(player, quest));
        engine.dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 1);
        assertEquals(QuestStatus.COMPLETED, store.status(player, quest.id()), "and finished at " + iso);
    }

    @Test
    void aForcedRunDoesNotReArmAQuestFinishedInThatYearsRun() {
        runs.run(2026, "2026-10-01", "2026-11-03").run(2027, "2027-10-01", "2027-11-03");
        Quest quest = onceARun(1, 1, 0);
        QuestEngine engine = engine(quest);
        finishAt(engine, quest, "2026-10-10T12:00:00Z");
        assertEquals(Integer.valueOf(2026), store.completions(player, quest.id()).runYear());

        runs.force(true);
        assertFalse(offeredAt(engine, quest, "2026-12-10T12:00:00Z"),
                "forced on in December it is still the 2026 run, already finished");
        QuestEngine.AcceptCheck check = engine.canAccept(player, quest);
        assertEquals(List.of(QuestGates.REASON_RUN_SPENT), check.reasons());
        assertEquals(FakeRuns.at("2027-10-01T00:00:00Z") - clock.get(), check.waitMs(),
                "it comes back when the event's next run starts");
        assertEquals(QuestStatus.ON_COOLDOWN, engine.status(player, quest));

        runs.force(null);
        assertTrue(offeredAt(engine, quest, "2027-10-02T12:00:00Z"), "the 2027 run is a new run");
    }

    @Test
    void reDatingTheWindowMidRunDoesNotReArmIt() {
        runs.run(2026, "2026-11-20", "2026-12-01");
        Quest quest = onceARun(1, 1, 0);
        QuestEngine engine = engine(quest);
        finishAt(engine, quest, "2026-11-22T12:00:00Z");

        runs.run(2026, "2026-11-18", "2026-12-05");
        assertFalse(offeredAt(engine, quest, "2026-11-25T12:00:00Z"), "a run whose days widened is the same run");

        runs.run(2026, "2026-11-26", "2026-12-10");
        assertFalse(offeredAt(engine, quest, "2026-11-25T12:00:00Z"), "between the old start and the new, nothing runs");
        assertFalse(offeredAt(engine, quest, "2026-11-28T12:00:00Z"), "and once it runs again it is still the 2026 run");
        assertEquals(1, store.completions(player, quest.id()).totalCount(), "nothing was paid twice");
    }

    @Test
    void aYearCrossingRunCountsCompletionsForItsStartYear() {
        runs.run(2026, "2026-12-15", "2027-01-06").run(2027, "2027-12-15", "2028-01-06");
        Quest quest = onceARun(1, 1, 0);
        QuestEngine engine = engine(quest);
        finishAt(engine, quest, "2027-01-03T12:00:00Z");
        assertEquals(Integer.valueOf(2026), store.completions(player, quest.id()).runYear(),
                "January's finish counts for the run that began in December");
        assertFalse(offeredAt(engine, quest, "2027-01-05T12:00:00Z"));
        assertTrue(offeredAt(engine, quest, "2027-12-20T12:00:00Z"), "the run that begins in December 2027 is the next one");
    }

    @Test
    void anOldRecordWithoutRunYearCountsForItsOwnRun() {
        runs.run(2026, "2026-10-01", "2026-11-03").run(2027, "2027-10-01", "2027-11-03");
        Quest quest = onceARun(1, 1, 0);
        QuestEngine engine = engine(quest);
        // Saved while the quest still came round on a rolling wait: one finish in the 2026 run, no run year.
        store.setStatus(player, quest.id(), QuestStatus.COMPLETED);
        store.setCompletions(player, quest.id(),
                new QuestProgressStore.CompletionRecord(FakeRuns.at("2026-10-15T12:00:00Z"), 0, 1, 1));
        assertNull(store.completions(player, quest.id()).runYear());

        assertFalse(offeredAt(engine, quest, "2026-10-20T12:00:00Z"),
                "a player who finished it in 2026 is not offered it again in 2026");
        assertTrue(offeredAt(engine, quest, "2027-10-02T12:00:00Z"), "but is in 2027");
    }

    @Test
    void progressCarriesOverToTheNextRunAndFinishingThereCountsForIt() {
        runs.run(2026, "2026-10-01", "2026-11-03").run(2027, "2027-10-01", "2027-11-03");
        Quest quest = onceARun(1, 3, 0);
        QuestEngine engine = engine(quest);
        clock.set(FakeRuns.at("2026-10-30T12:00:00Z"));
        assertTrue(engine.accept(player, quest));
        engine.dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 1);

        clock.set(FakeRuns.at("2026-12-01T12:00:00Z"));
        engine.selfHeal(player);
        assertEquals(QuestStatus.ACTIVE, engine.status(player, quest), "the run ending takes nothing away");
        assertEquals(1, engine.progressOf(player, quest.id(), "logs").current());
        assertFalse(quest.available(), "and it is offered to nobody between runs");

        clock.set(FakeRuns.at("2027-10-02T12:00:00Z"));
        engine.selfHeal(player);
        assertEquals(1, engine.progressOf(player, quest.id(), "logs").current(), "it resumes where it stopped");
        engine.dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 2);
        assertEquals(QuestStatus.COMPLETED, store.status(player, quest.id()));
        assertEquals(Integer.valueOf(2027), store.completions(player, quest.id()).runYear(),
                "finishing it counts for the new run");
        assertFalse(offeredAt(engine, quest, "2027-10-20T12:00:00Z"));
    }

    // M242: an unfinished yearly quest hides with the season and comes back where the player left it.
    @Test
    void anUnfinishedQuestHidesWithItsSeasonAndComesBackWhereItWasLeft() {
        runs.run(2026, "2026-10-01", "2026-11-03").run(2027, "2027-10-01", "2027-11-03");
        Quest quest = onceARun(1, 3, 0);
        QuestEngine engine = engine(quest);
        clock.set(FakeRuns.at("2026-10-30T12:00:00Z"));
        assertTrue(engine.accept(player, quest));
        assertTrue(engine.track(player, quest.id()));
        engine.dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 1);
        assertEquals(List.of(quest.id()), engine.trackedActive(player).stream().map(Quest::id).toList(),
                "on the tracker while its run is on");

        clock.set(FakeRuns.at("2026-12-01T12:00:00Z"));
        engine.selfHeal(player);
        assertFalse(quest.available(), "between runs the journal, which lists only what is available, hides it");
        assertTrue(engine.trackedActive(player).isEmpty(), "and so does the tracked-quest HUD");
        assertEquals(List.of(quest.id()), engine.tracked(player), "its pin is kept: a pin is the player's choice");
        assertEquals(QuestStatus.ACTIVE, engine.status(player, quest), "nothing is taken away");
        assertEquals(1, engine.progressOf(player, quest.id(), "logs").current());

        clock.set(FakeRuns.at("2027-10-02T12:00:00Z"));
        engine.selfHeal(player);
        assertTrue(quest.available(), "the next run puts it back in the journal");
        assertEquals(List.of(quest.id()), engine.trackedActive(player).stream().map(Quest::id).toList(),
                "and on the tracker");
        assertEquals(1, engine.progressOf(player, quest.id(), "logs").current(), "where the player left it");
    }

    @Test
    void timesAllowsThatManyFinishesARunAndTheLifetimeCapStillComesFirst() {
        runs.run(2026, "2026-10-01", "2026-11-03").run(2027, "2027-10-01", "2027-11-03");
        Quest quest = onceARun(2, 1, 3);
        QuestEngine engine = engine(quest);
        finishAt(engine, quest, "2026-10-05T12:00:00Z");
        finishAt(engine, quest, "2026-10-06T12:00:00Z");
        assertEquals(2, store.completions(player, quest.id()).runCount());
        assertFalse(offeredAt(engine, quest, "2026-10-07T12:00:00Z"), "two finishes spend the run");

        finishAt(engine, quest, "2027-10-02T12:00:00Z");
        assertFalse(offeredAt(engine, quest, "2027-10-03T12:00:00Z"));
        assertEquals(List.of(QuestGates.REASON_MAX_COMPLETIONS), engine.canAccept(player, quest).reasons(),
                "three finishes spend the lifetime cap, which is the truer thing to say");
    }

    @Test
    void aQuestWithoutPerRunIsUntouched() {
        Quest daily = Quest.builder("q_daily").repeat(Quest.Repeat.every(3_600_000L)).build();
        assertTrue(daily.inRun());
        assertTrue(daily.available(), "no event is asked about at all");
    }
}
