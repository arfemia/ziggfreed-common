package com.ziggfreed.common.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.subject.Subject;

/**
 * The RE-ARM report: every path that puts a quest back to pristine has to say so, because a layer
 * above this module keeps state declared to live only as long as that quest and has no other way to
 * learn it is over. Each case here is a path that reported nothing while the clearing was a
 * consumer's own business, which is exactly how a declared lifetime became a promise one mod kept.
 */
class QuestResetsTest {

    private InMemoryQuestProgressStore store;
    private AtomicLong clock;
    private Subject player;
    private List<String> reArmed;

    @BeforeEach
    void setUp() {
        store = new InMemoryQuestProgressStore();
        clock = new AtomicLong(1_000_000L);
        player = Subject.of(UUID.randomUUID(), "tester");
        reArmed = new ArrayList<>();
        QuestResets.install((subject, questId) -> reArmed.add(questId));
    }

    @AfterEach
    void tearDown() {
        QuestResets.reset();
    }

    @Nonnull
    private QuestEngine engineWith(@Nonnull Quest... quests) {
        QuestEngine engine = QuestEngine.builder()
                .store(store)
                .clock(clock::get)
                .nativeEvents(false)
                .warn(message -> { })
                .build();
        engine.setQuests(List.of(quests));
        return engine;
    }

    @Test
    void abandonReportsTheReArm() {
        Quest giveUp = Quest.builder("give_up").build();
        QuestEngine engine = engineWith(giveUp);
        assertTrue(engine.accept(player, giveUp), "accepted");

        assertTrue(engine.abandon(player, "give_up"), "abandoned");

        assertEquals(List.of("give_up"), reArmed);
    }

    @Test
    void aRepeatableComingRoundReportsTheReArm() {
        Quest daily = Quest.builder("daily").repeat(Quest.Repeat.every(60_000L)).build();
        QuestEngine engine = engineWith(daily);
        store.setStatus(player, "daily", QuestStatus.COMPLETED);
        store.setCooldownStamp(player, "daily", clock.get());

        clock.addAndGet(120_000L);
        assertEquals(1, engine.selfHeal(player), "the cooled-down repeatable was re-armed");

        assertEquals(List.of("daily"), reArmed);
    }

    @Test
    void nothingIsReportedWhenNoQuestWasReArmed() {
        Quest carried = Quest.builder("carried").build();
        QuestEngine engine = engineWith(carried);
        assertTrue(engine.accept(player, carried), "accepted");

        engine.selfHeal(player);

        assertTrue(reArmed.isEmpty(), "an active quest is not a re-arm");
    }

    @Test
    void anAdminWipeDropsTheCompletionRecordAndStillReportsTheReArm() {
        Quest daily = Quest.builder("daily").repeat(Quest.Repeat.every(60_000L)).build();
        QuestEngine engine = engineWith(daily);
        store.setStatus(player, "daily", QuestStatus.COMPLETED);
        store.setCooldownStamp(player, "daily", clock.get());
        store.setCompletions(player, "daily",
                new QuestProgressStore.CompletionRecord(clock.get(), 1, 4, 4));

        engine.wipeQuest(player, "daily");

        assertEquals(QuestStatus.NOT_STARTED, store.status(player, "daily"), "re-armed");
        assertEquals(QuestProgressStore.CompletionRecord.NONE, store.completions(player, "daily"),
                "an administrator's wipe drops the record an in-play re-arm keeps");
        assertEquals(List.of("daily"), reArmed, "and the re-arm is reported like any other");
    }

    @Test
    void wipingEverythingWipesEachKnownQuestAndReportsEachOne() {
        Quest first = Quest.builder("first").build();
        Quest second = Quest.builder("second").build();
        QuestEngine engine = engineWith(first, second);
        assertTrue(engine.accept(player, first), "accepted");
        assertTrue(engine.accept(player, second), "accepted");

        assertEquals(2, engine.wipeAllQuests(player), "both known ids were wiped");

        assertEquals(QuestStatus.NOT_STARTED, store.status(player, "first"));
        assertEquals(QuestStatus.NOT_STARTED, store.status(player, "second"));
        assertEquals(2, reArmed.size(), "every wiped quest reported its re-arm");
        assertTrue(reArmed.containsAll(List.of("first", "second")));
        assertEquals(0, engine.wipeAllQuests(player), "a second wipe finds nothing left");
    }

    @Test
    void aThrowingListenerCostsOnlyItself() {
        QuestResets.install((subject, questId) -> {
            throw new IllegalStateException("listener is broken");
        });
        Quest giveUp = Quest.builder("give_up").build();
        QuestEngine engine = engineWith(giveUp);
        assertTrue(engine.accept(player, giveUp), "accepted");

        assertTrue(engine.abandon(player, "give_up"), "the abandon still succeeds");
        assertEquals(QuestStatus.NOT_STARTED, store.status(player, "give_up"),
                "and the quest really was re-armed");
    }
}
