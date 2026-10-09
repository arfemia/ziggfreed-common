package com.ziggfreed.common.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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
 * year counts for the run nearest its last finish; and an unfinished quest keeps its progress into the
 * next run, where finishing it counts, unless it does not carry over: then it is dropped on the first read
 * after the run it was taken in is over. Runs are keyed (event, year, number), never by whether now is in them,
 * and come in time order by (year, start), never by number: a number names a run, it does not place it.
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

    /** One step of {@code logs} logs, once a run, whose progress does NOT carry to the next run. */
    @Nonnull
    private static Quest weekly(int logs) {
        return Quest.builder("q_weekly")
                .objective(ObjectiveDef.builder("logs", "BREAK_BLOCK")
                        .target("Oak_Log").matchMode(MatchMode.EXACT).amount(logs).build())
                .repeat(new Quest.Repeat(0L, Quest.Repeat.CooldownFrom.CLAIM, null, 0,
                        new Quest.Repeat.PerRun(EVENT, 1, false)))
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
        // A released four-number save of a quest retrofitted to PerRun (the yearly lantern quest) is
        // exactly this record, and its retrofit relies on it reading as spent for its own run.
        store.setStatus(player, quest.id(), QuestStatus.COMPLETED);
        store.setCompletions(player, quest.id(),
                new QuestProgressStore.CompletionRecord(FakeRuns.at("2026-10-15T12:00:00Z"), 0, 1, 1));
        assertNull(store.completions(player, quest.id()).runYear());

        assertFalse(offeredAt(engine, quest, "2026-10-20T12:00:00Z"),
                "a player who finished it in 2026 is not offered it again in 2026");
        assertTrue(offeredAt(engine, quest, "2027-10-02T12:00:00Z"), "but is in 2027");
    }

    /** A save from before the run tally (four numbers, no run year): one finish of {@code quest} at {@code iso}. */
    private void savedBeforeTheRunTally(@Nonnull Quest quest, @Nonnull String iso) {
        store.setStatus(player, quest.id(), QuestStatus.COMPLETED);
        store.setCompletions(player, quest.id(),
                new QuestProgressStore.CompletionRecord(FakeRuns.at(iso), 0, 1, 1));
    }

    /**
     * The run year {@code record} counts for when read at {@code iso}, read as the engine reads it. The reading
     * moment moves nothing: an old record's run depends on the event's dates alone.
     */
    @Nullable
    private Integer countedFor(@Nonnull QuestProgressStore.CompletionRecord record, @Nonnull String iso) {
        PerRuns.RunKey run = PerRuns.runOf(record, new Quest.Repeat.PerRun(EVENT, 1), runs);
        return run == null ? null : run.year();
    }

    /**
     * The yearly lantern case: a save from before the run tally holds a finish on October 15, and mid-run
     * the owner moves the event's start from October 1 to October 20. The run no longer holds the finish,
     * yet it is still the run nearest it, so the finish stays spent there, by its new dates and in a run
     * forced on before them.
     */
    @Test
    void anOldRecordStaysSpentForItsRunAfterTheOwnerMovesTheStartPastItsFinish() {
        runs.run(2025, "2025-10-01", "2025-11-03").run(2026, "2026-10-01", "2026-11-03")
                .run(2027, "2027-10-01", "2027-11-03");
        Quest quest = onceARun(1, 1, 0);
        QuestEngine engine = engine(quest);
        savedBeforeTheRunTally(quest, "2026-10-15T12:00:00Z");

        runs.run(2025, "2025-10-20", "2025-11-03").run(2026, "2026-10-20", "2026-11-03")
                .run(2027, "2027-10-20", "2027-11-03");
        runs.force(true);
        assertFalse(offeredAt(engine, quest, "2026-10-18T12:00:00Z"),
                "forced on before its new start, it is the run the finish was in, and spent");

        runs.force(null);
        assertFalse(offeredAt(engine, quest, "2026-10-25T12:00:00Z"),
                "by its new dates too: the run that starts five days after the finish is the one it was in");
        assertEquals(List.of(QuestGates.REASON_RUN_SPENT), engine.canAccept(player, quest).reasons());
        assertEquals(Integer.valueOf(2026), countedFor(store.completions(player, quest.id()), "2026-10-25T12:00:00Z"));
        assertEquals(1, store.completions(player, quest.id()).totalCount(), "nothing was paid twice");
    }

    @Test
    void thatOldRecordIsNotSpentForTheNextYearsRun() {
        runs.run(2025, "2025-10-20", "2025-11-03").run(2026, "2026-10-20", "2026-11-03")
                .run(2027, "2027-10-20", "2027-11-03");
        Quest quest = onceARun(1, 1, 0);
        QuestEngine engine = engine(quest);
        savedBeforeTheRunTally(quest, "2026-10-15T12:00:00Z");

        finishAt(engine, quest, "2027-10-25T12:00:00Z");
        assertEquals(Integer.valueOf(2027), store.completions(player, quest.id()).runYear(),
                "the 2027 run is a new run, and finishing it there counts for it");
        assertEquals(1, store.completions(player, quest.id()).runCount(), "as its first finish");
        assertFalse(offeredAt(engine, quest, "2027-10-26T12:00:00Z"));
    }

    @Test
    void anOldRecordInAYearCrossingRunCountsForItsDecemberYearAlsoAfterASmallReDate() {
        runs.run(2026, "2026-12-15", "2027-01-06").run(2027, "2027-12-15", "2028-01-06");
        Quest quest = onceARun(1, 1, 0);
        QuestEngine engine = engine(quest);
        savedBeforeTheRunTally(quest, "2027-01-03T12:00:00Z");
        QuestProgressStore.CompletionRecord old = store.completions(player, quest.id());
        assertEquals(Integer.valueOf(2026), countedFor(old, "2027-01-04T12:00:00Z"),
                "the run that began in December holds the January finish");

        runs.run(2026, "2026-12-20", "2027-01-02").run(2027, "2027-12-20", "2028-01-02");
        assertEquals(Integer.valueOf(2026), countedFor(old, "2027-01-04T12:00:00Z"),
                "moved to end on January 2, the December run is still the nearest");
        assertTrue(offeredAt(engine, quest, "2027-12-21T12:00:00Z"),
                "and the run that begins in December 2027 is the next one");
    }

    @Test
    void aRecordWithARunYearKeepsItWhateverRunIsNearestItsFinish() {
        runs.run(2026, "2026-10-01", "2026-11-03").run(2027, "2027-10-01", "2027-11-03");
        Quest quest = onceARun(1, 1, 0);
        QuestEngine engine = engine(quest);
        runs.force(true);
        finishAt(engine, quest, "2027-04-10T12:00:00Z");
        QuestProgressStore.CompletionRecord forced = store.completions(player, quest.id());
        assertEquals(Integer.valueOf(2027), forced.runYear(), "forced on in April it is the 2027 run");
        QuestProgressStore.CompletionRecord asIfOld =
                new QuestProgressStore.CompletionRecord(forced.lastCompletionMs(), 0, 1, 1);
        assertEquals(Integer.valueOf(2026), countedFor(asIfOld, "2027-04-11T12:00:00Z"),
                "as an old record the same finish would sit nearer the 2026 run");

        runs.force(null);
        assertEquals(Integer.valueOf(2027), countedFor(forced, "2027-10-02T12:00:00Z"), "its own run year stands");
        assertFalse(offeredAt(engine, quest, "2027-10-02T12:00:00Z"), "so the 2027 run is spent");
    }

    @Test
    void anOldRecordAsFarFromTwoRunsCountsForTheEarlier() {
        runs.run(2025, "2025-03-01", "2025-08-31").run(2026, "2026-03-01", "2026-08-31")
                .run(2027, "2027-03-01", "2027-08-31");
        // 90 and a half days after the 2026 run ends on September 1, and as long before the 2027 run starts.
        long midway = FakeRuns.at("2026-11-30T12:00:00Z");
        assertEquals(Integer.valueOf(2026),
                countedFor(new QuestProgressStore.CompletionRecord(midway, 0, 1, 1), "2027-04-01T12:00:00Z"),
                "a tie goes to the earlier run");
        assertEquals(Integer.valueOf(2027),
                countedFor(new QuestProgressStore.CompletionRecord(midway + 1L, 0, 1, 1), "2027-04-01T12:00:00Z"),
                "a moment later the later run is the nearer");
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

    /**
     * Runs only move forward. A record already counted for a later run (here the 2027 run, as a save
     * made before this rule could hold it: a carried quest finished between runs) reads as spent for an
     * earlier run forced on later in 2026, so the quest is paid at most once across the two.
     */
    @Test
    void aRecordCountedForALaterRunIsSpentForAnEarlierForcedRunSoNoRunPaysTwice() {
        runs.run(2026, "2026-10-01", "2026-11-03").run(2027, "2027-10-01", "2027-11-03");
        Quest quest = onceARun(1, 1, 0);
        QuestEngine engine = engine(quest);
        store.setStatus(player, quest.id(), QuestStatus.COMPLETED);
        store.setCompletions(player, quest.id(),
                new QuestProgressStore.CompletionRecord(FakeRuns.at("2026-11-20T12:00:00Z"), 0, 1, 1, 2027, 1));

        runs.force(true);
        assertFalse(offeredAt(engine, quest, "2026-12-10T12:00:00Z"),
                "forced on later in 2026, a quest already counted for 2027 is spent for 2026");
        assertEquals(List.of(QuestGates.REASON_RUN_SPENT), engine.canAccept(player, quest).reasons());
        assertFalse(engine.accept(player, quest), "and nothing takes it");

        runs.force(null);
        assertFalse(offeredAt(engine, quest, "2027-10-02T12:00:00Z"), "the 2027 run it counted for is spent too");
        assertEquals(1, store.completions(player, quest.id()).totalCount(), "one payout across both runs");
        assertEquals(Integer.valueOf(2027), store.completions(player, quest.id()).runYear());
    }

    @Test
    void aCloseOutInAnEarlierRunNeverMovesTheRecordBack() {
        runs.run(2026, "2026-10-01", "2026-11-03").run(2027, "2027-10-01", "2027-11-03");
        Quest quest = onceARun(1, 1, 0);
        QuestEngine engine = engine(quest);
        store.setStatus(player, quest.id(), QuestStatus.COMPLETED);
        store.setCompletions(player, quest.id(),
                new QuestProgressStore.CompletionRecord(FakeRuns.at("2026-11-20T12:00:00Z"), 0, 1, 1, 2027, 1));

        runs.force(true);
        clock.set(FakeRuns.at("2026-12-10T12:00:00Z"));
        assertTrue(engine.forceComplete(player, quest), "an administrator closes it out in the forced 2026 run");
        assertEquals(Integer.valueOf(2027), store.completions(player, quest.id()).runYear(),
                "the finish joins the run the record already counts for, never an earlier one");
        assertEquals(2, store.completions(player, quest.id()).runCount());

        runs.force(null);
        assertFalse(offeredAt(engine, quest, "2027-10-02T12:00:00Z"), "so the 2027 run cannot pay it again");
    }

    @Test
    void aCarriedQuestCountsNoProgressBetweenRunsAndCountsAgainOnceTheNextRunStarts() {
        runs.run(2026, "2026-10-01", "2026-11-03").run(2027, "2027-10-01", "2027-11-03");
        Quest quest = onceARun(1, 3, 0);
        QuestEngine engine = engine(quest);
        clock.set(FakeRuns.at("2026-10-30T12:00:00Z"));
        assertTrue(engine.accept(player, quest));
        engine.dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 1);

        clock.set(FakeRuns.at("2026-12-01T12:00:00Z"));
        engine.dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 5);
        assertEquals(1, engine.progressOf(player, quest.id(), "logs").current(), "between runs nothing counts");
        assertEquals(QuestStatus.ACTIVE, store.status(player, quest.id()), "so it cannot finish between runs");

        clock.set(FakeRuns.at("2027-10-02T12:00:00Z"));
        engine.dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 1);
        assertEquals(2, engine.progressOf(player, quest.id(), "logs").current(), "once the run starts it counts");
    }

    // Carry off: a weekly or monthly quest starts afresh each run. The drop is lazy: the first read after the run
    // it was taken in is over.
    @Test
    void aQuestThatDoesNotCarryIsDroppedOnTheFirstReadAfterItsRunEnds() {
        runs.run(2026, 1, "2026-10-03", "2026-10-03").run(2026, 2, "2026-10-10", "2026-10-10");
        Quest quest = weekly(3);
        QuestEngine engine = engine(quest);
        clock.set(FakeRuns.at("2026-10-03T12:00:00Z"));
        assertTrue(engine.accept(player, quest));
        engine.dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 1);
        assertTrue(engine.track(player, quest.id()));

        clock.set(FakeRuns.at("2026-10-03T20:00:00Z"));
        engine.selfHeal(player);
        assertEquals(QuestStatus.ACTIVE, store.status(player, quest.id()), "kept while its run is on");

        clock.set(FakeRuns.at("2026-10-05T12:00:00Z"));
        engine.selfHeal(player);
        assertEquals(QuestStatus.NOT_STARTED, store.status(player, quest.id()), "its run is over: dropped between runs");
        assertTrue(QuestProgressPayload.deserialize(store.progressPayload(player, quest.id())).isEmpty(),
                "with its progress");
        assertTrue(engine.tracked(player).isEmpty(), "and its pin");
        assertTrue(store.completions(player, quest.id()).isEmpty(), "nothing was finished, so nothing is recorded");
        assertTrue(offeredAt(engine, quest, "2026-10-10T12:00:00Z"), "the next run offers it afresh");
    }

    @Test
    void aPlayerAwayAcrossTheRunsEndFindsLastRunsQuestDroppedBeforeAnythingCounts() {
        runs.run(2026, 1, "2026-10-03", "2026-10-03").run(2026, 2, "2026-10-10", "2026-10-10");
        Quest quest = weekly(3);
        QuestEngine engine = engine(quest);
        clock.set(FakeRuns.at("2026-10-03T12:00:00Z"));
        assertTrue(engine.accept(player, quest));
        engine.dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 2);

        // No read between the runs: the first is a dispatch in the next run.
        clock.set(FakeRuns.at("2026-10-10T12:00:00Z"));
        engine.dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 1);
        assertEquals(QuestStatus.NOT_STARTED, store.status(player, quest.id()),
                "the dispatch drops last run's quest before it could count toward it");
        assertTrue(engine.canAccept(player, quest).allowed(), "and this run's is on offer");
        assertTrue(engine.accept(player, quest));
        engine.dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 1);
        assertEquals(1, engine.progressOf(player, quest.id(), "logs").current(), "counting from nothing");
    }

    @Test
    void aQuestThatDoesNotCarryIsKeptWhileItsRunGoesOnWhereverItsDaysMove() {
        runs.run(2026, 1, "2026-10-03", "2026-10-04").run(2026, 2, "2026-10-10", "2026-10-11");
        Quest quest = weekly(3);
        QuestEngine engine = engine(quest);
        clock.set(FakeRuns.at("2026-10-03T12:00:00Z"));
        assertTrue(engine.accept(player, quest));
        engine.dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 1);
        runs.run(2026, 1, "2026-10-02", "2026-10-06"); // the owner widens this run
        clock.set(FakeRuns.at("2026-10-05T12:00:00Z"));
        engine.selfHeal(player);
        assertEquals(QuestStatus.ACTIVE, store.status(player, quest.id()), "the same run, still going on");
        assertEquals(1, engine.progressOf(player, quest.id(), "logs").current());
    }

    // M287: several runs a year. A run is (event, year, number), and each run is its own once.
    @Test
    void aQuestFinishedInTheSpringRunComesBackForTheAutumnRun() {
        runs.run(2026, 1, "2026-04-10", "2026-04-16").run(2026, 2, "2026-09-20", "2026-09-26")
                .run(2027, 1, "2027-04-10", "2027-04-16");
        Quest quest = onceARun(1, 1, 0);
        QuestEngine engine = engine(quest);
        finishAt(engine, quest, "2026-04-12T12:00:00Z");
        assertEquals(Integer.valueOf(2026), store.completions(player, quest.id()).runYear());
        assertEquals(1, store.completions(player, quest.id()).runNumber());

        assertFalse(offeredAt(engine, quest, "2026-04-14T12:00:00Z"), "the spring run is spent");
        assertEquals(FakeRuns.at("2026-09-20T00:00:00Z") - clock.get(), engine.canAccept(player, quest).waitMs(),
                "it comes back with the autumn run, the same year's second");

        finishAt(engine, quest, "2026-09-21T12:00:00Z");
        QuestProgressStore.CompletionRecord autumn = store.completions(player, quest.id());
        assertEquals(Integer.valueOf(2026), autumn.runYear(), "a run is still filed under the year it starts in");
        assertEquals(2, autumn.runNumber());
        assertEquals(1, autumn.runCount(), "the autumn run's own tally starts afresh");
        assertFalse(offeredAt(engine, quest, "2026-09-22T12:00:00Z"));
        assertEquals(FakeRuns.at("2027-04-10T00:00:00Z") - clock.get(), engine.canAccept(player, quest).waitMs());
    }

    // Review Focus 2, several runs a year: a force brings the year's next run forward, and that run's own dates
    // later are the same run, so nothing is paid twice.
    @Test
    void aForcedRunIsTheYearsNextRunAndItsOwnDatesDoNotReArmIt() {
        runs.run(2026, 1, "2026-04-10", "2026-04-16").run(2026, 2, "2026-09-20", "2026-09-26")
                .run(2027, 1, "2027-04-10", "2027-04-16");
        Quest quest = onceARun(1, 1, 0);
        QuestEngine engine = engine(quest);
        finishAt(engine, quest, "2026-04-12T12:00:00Z");

        runs.force(true);
        finishAt(engine, quest, "2026-06-01T12:00:00Z");
        assertEquals(2, store.completions(player, quest.id()).runNumber(), "forced in June it is the autumn run");

        runs.force(null);
        assertFalse(offeredAt(engine, quest, "2026-09-21T12:00:00Z"),
                "when the autumn run's own days come it is the run already finished");
        runs.force(true);
        assertFalse(offeredAt(engine, quest, "2026-10-15T12:00:00Z"),
                "forced after every run of the year is over, it is the year's last run again");
        assertEquals(2, store.completions(player, quest.id()).totalCount(), "two runs, two finishes, never three");
    }

    // The question behind M287: a run the player spent, moved by the owner to start later, is never the run the
    // wait names; the wait names the next run the player has not spent.
    @Test
    void aSpentRunMovedLaterIsSkippedByTheWait() {
        runs.run(2026, "2026-10-01", "2026-11-03").run(2027, "2027-10-01", "2027-11-03");
        Quest quest = onceARun(1, 1, 0);
        QuestEngine engine = engine(quest);
        finishAt(engine, quest, "2026-10-10T12:00:00Z");

        runs.run(2026, "2026-12-01", "2026-12-20");
        assertFalse(offeredAt(engine, quest, "2026-10-20T12:00:00Z"), "nothing runs between the old days and the new");
        QuestEngine.AcceptCheck check = engine.canAccept(player, quest);
        assertTrue(check.reasons().contains(QuestGates.REASON_RUN_SPENT));
        assertEquals(FakeRuns.at("2027-10-01T00:00:00Z") - clock.get(), check.waitMs(),
                "the moved 2026 run is spent, so the wait names 2027's, never December's");
        assertFalse(offeredAt(engine, quest, "2026-12-05T12:00:00Z"), "and in December it is still the spent run");
    }

    @Test
    void aSpentSpringRunMovedLaterIsSkippedForTheAutumnRun() {
        runs.run(2026, 1, "2026-04-10", "2026-04-16").run(2026, 2, "2026-09-20", "2026-09-26");
        Quest quest = onceARun(1, 1, 0);
        QuestEngine engine = engine(quest);
        finishAt(engine, quest, "2026-04-12T12:00:00Z");
        // The owner moves the spring fair into June: still the year's first run.
        runs.run(2026, 1, "2026-06-01", "2026-06-07");
        assertFalse(offeredAt(engine, quest, "2026-05-01T12:00:00Z"));
        assertEquals(FakeRuns.at("2026-09-20T00:00:00Z") - clock.get(), engine.canAccept(player, quest).waitMs(),
                "the moved spring run is spent, so the wait names the autumn run");
        assertFalse(offeredAt(engine, quest, "2026-06-03T12:00:00Z"), "and the moved spring run pays nothing twice");
    }

    // Review Focus 5, several runs a year: a second run crossing the new year counts for the year it started.
    @Test
    void aSecondRunCrossingTheNewYearCountsForTheYearItStartedAndAnOldRecordForItsOwnRun() {
        runs.run(2026, 1, "2026-06-01", "2026-06-07").run(2026, 2, "2026-12-20", "2027-01-05")
                .run(2027, 1, "2027-06-01", "2027-06-07");
        Quest quest = onceARun(1, 1, 0);
        QuestEngine engine = engine(quest);
        finishAt(engine, quest, "2027-01-03T12:00:00Z");
        QuestProgressStore.CompletionRecord record = store.completions(player, quest.id());
        assertEquals(Integer.valueOf(2026), record.runYear(), "January's finish counts for the run that began in December");
        assertEquals(2, record.runNumber());
        assertFalse(offeredAt(engine, quest, "2027-01-04T12:00:00Z"));
        assertTrue(offeredAt(engine, quest, "2027-06-02T12:00:00Z"), "2027's first run is a new run");

        // A record saved before the run tally existed belongs to the run its last finish fell in, by number too.
        store.setStatus(player, quest.id(), QuestStatus.COMPLETED);
        store.setCompletions(player, quest.id(),
                new QuestProgressStore.CompletionRecord(FakeRuns.at("2027-06-03T12:00:00Z"), 0, 1, 1));
        assertFalse(offeredAt(engine, quest, "2027-06-04T12:00:00Z"), "spent in the run it fell in");
    }

    // M351: a run's number names it and need not follow its days (a list of spans keeps each span's place in the
    // list wherever its days move). Runs come round by (year, start), so a run numbered first but dated after
    // another is the later run.
    @Test
    void aRunNumberedFirstButDatedAfterTheSecondIsTheLaterRun() {
        runs.run(2026, 1, "2026-09-20", "2026-09-26").run(2026, 2, "2026-04-10", "2026-04-16")
                .run(2027, 1, "2027-09-20", "2027-09-26").run(2027, 2, "2027-04-10", "2027-04-16");
        Quest quest = onceARun(1, 1, 0);
        QuestEngine engine = engine(quest);
        finishAt(engine, quest, "2026-04-12T12:00:00Z");
        assertEquals(2, store.completions(player, quest.id()).runNumber(), "April's run is the list's second span");

        assertFalse(offeredAt(engine, quest, "2026-04-14T12:00:00Z"));
        assertEquals(FakeRuns.at("2026-09-20T00:00:00Z") - clock.get(), engine.canAccept(player, quest).waitMs(),
                "it comes back with September's run 1, which comes after April's run 2");
        finishAt(engine, quest, "2026-09-21T12:00:00Z");
        assertEquals(1, store.completions(player, quest.id()).runNumber(), "a later run, though a lower number");
        assertFalse(offeredAt(engine, quest, "2026-09-22T12:00:00Z"));
        assertEquals(FakeRuns.at("2027-04-10T00:00:00Z") - clock.get(), engine.canAccept(player, quest).waitMs(),
                "and then with the next year's first run in time, its run 2");
        assertEquals(2, store.completions(player, quest.id()).totalCount());
    }

    /**
     * Runs only move forward in time, not in number: a record counted for September's run 1 (a close-out
     * between runs counts for the next run) reads as spent for the year's run 2 once an owner moves it to May,
     * since May comes before September, though 2 is the higher number.
     */
    @Test
    void aRecordCountedForARunDatedLaterIsSpentForAnEarlierRunWhateverItsNumber() {
        runs.run(2026, 1, "2026-09-20", "2026-09-26").run(2026, 2, "2026-04-10", "2026-04-16")
                .run(2027, 1, "2027-09-20", "2027-09-26").run(2027, 2, "2027-04-10", "2027-04-16");
        Quest quest = onceARun(1, 1, 0);
        QuestEngine engine = engine(quest);
        store.setStatus(player, quest.id(), QuestStatus.COMPLETED);
        store.setCompletions(player, quest.id(),
                new QuestProgressStore.CompletionRecord(FakeRuns.at("2026-04-20T12:00:00Z"), 0, 1, 1, 2026, 1, 1));

        runs.run(2026, 2, "2026-05-01", "2026-05-07");
        assertFalse(offeredAt(engine, quest, "2026-05-03T12:00:00Z"),
                "May's run 2 comes before the September run the record counts for, so it cannot pay again");
        QuestEngine.AcceptCheck check = engine.canAccept(player, quest);
        assertEquals(List.of(QuestGates.REASON_RUN_SPENT), check.reasons());
        assertEquals(FakeRuns.at("2027-04-10T00:00:00Z") - clock.get(), check.waitMs(),
                "September's run is the one counted, so the wait names the first run after it, 2027's April run");
        assertTrue(offeredAt(engine, quest, "2027-04-12T12:00:00Z"));
    }

    /**
     * YZ1c's concern H: the calendar follows a run it set aside (moved onto another, it met a run written before
     * it) from that run's own start, which can come before the run it met, so the wait can name that run. Time
     * order agrees: the run met starts after the spent run did, so it is a new run, and the wait never names a run
     * the quest is still spent for.
     */
    @Test
    void theWaitAfterASpentRunSetAsideCanNameTheRunItMetAndThatRunIsOffered() {
        runs.run(2026, 1, "2026-04-10", "2026-04-16").run(2026, 2, "2026-09-01", "2026-09-07")
                .run(2027, 1, "2027-04-10", "2027-04-16");
        Quest quest = onceARun(1, 1, 0);
        QuestEngine engine = engine(quest);
        finishAt(engine, quest, "2026-09-03T12:00:00Z");
        assertEquals(2, store.completions(player, quest.id()).runNumber());

        // The owner moves the spring run into September: the autumn run, written after it, meets it and is set aside.
        runs.run(2026, 1, "2026-09-05", "2026-09-26").setAside(2026, 2, "2026-09-01", "2026-09-07");
        assertEquals(1, runs.after(EVENT, 2026, 2).number(),
                "the calendar follows the set-aside run from its own start, to the run it met");
        assertFalse(offeredAt(engine, quest, "2026-09-04T12:00:00Z"), "nothing runs on the 4th");
        assertEquals(FakeRuns.at("2026-09-05T00:00:00Z") - clock.get(), engine.canAccept(player, quest).waitMs(),
                "the moved spring run starts after the spent run did, so the wait names it");
        assertTrue(offeredAt(engine, quest, "2026-09-06T12:00:00Z"), "and when it comes it is offered, as the wait said");
    }

    /** Concern H the other way: a run that starts before the spent run it met is an earlier run, so it is spent. */
    @Test
    void aRunStartingBeforeTheSpentRunItMetIsSpentAndTheWaitNamesTheRunAfterBoth() {
        runs.run(2026, 1, "2026-04-10", "2026-04-16").run(2026, 2, "2026-09-20", "2026-09-26")
                .run(2027, 1, "2027-04-10", "2027-04-16");
        Quest quest = onceARun(1, 1, 0);
        QuestEngine engine = engine(quest);
        finishAt(engine, quest, "2026-09-21T12:00:00Z");

        // The owner moves the spring run over the autumn one, which meets it and is set aside.
        runs.run(2026, 1, "2026-09-15", "2026-09-30").setAside(2026, 2, "2026-09-20", "2026-09-26");
        assertFalse(offeredAt(engine, quest, "2026-09-22T12:00:00Z"),
                "the spring run, moved over the autumn one, began before the run the record counts for");
        QuestEngine.AcceptCheck check = engine.canAccept(player, quest);
        assertEquals(List.of(QuestGates.REASON_RUN_SPENT), check.reasons());
        assertEquals(FakeRuns.at("2027-04-10T00:00:00Z") - clock.get(), check.waitMs(), "so the wait names 2027's");
        assertTrue(offeredAt(engine, quest, "2027-04-11T12:00:00Z"));
        assertEquals(1, store.completions(player, quest.id()).totalCount(), "nothing was paid twice");
    }

    @Test
    void aQuestWithoutPerRunIsUntouched() {
        Quest daily = Quest.builder("q_daily").repeat(Quest.Repeat.every(3_600_000L)).build();
        assertTrue(daily.inRun());
        assertTrue(daily.available(), "no event is asked about at all");
    }
}
