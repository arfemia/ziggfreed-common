package com.ziggfreed.common.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.progress.MatchMode;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.subject.Subject;

/**
 * What the engine OWES a consumer's persistence backend: every mutating path says the player changed
 * ({@code markDirty}), and three of them - and only three - say commit it now ({@code flush}).
 *
 * <p>The dirty doors pinned here are the PUBLIC ones an authoring layer calls with no engine method
 * in front of them: a re-arm from a rotating offer or a chained quest's pool, the two "this quest is
 * finished" rules a surface driving its own state machine calls, and the runtime FORWARDER a
 * consumer's registered store is reached through. A caller cannot be relied on to report a write it
 * did not know it was making, so the method that MAKES the write reports it. A backend hearing
 * nothing is invisible until the player's state reverts on their next hydrate, which is why the
 * obligation is asserted rather than left to review.
 *
 * <p>The commit half is asserted with EXACT counts, because its failure mode is the opposite one: a
 * commit at a moment the engine reached on its own multiplies across sweeps, so a login or a
 * threshold pass turns into one database write per entry it touched. Collecting commits
 * unconditionally (the player pressed the button); the auto-claim payout and a forced close-out
 * commit only when something was actually delivered; nothing else commits at all.
 */
class QuestEnginePersistenceReportTest {

    private RecordingQuestStore store;
    private Subject player;
    private RewardKindRegistry rewardKinds;

    @BeforeEach
    void setUp() {
        store = new RecordingQuestStore();
        player = Subject.of(UUID.randomUUID(), "tester");
        rewardKinds = new RewardKindRegistry();
        rewardKinds.register("NOTE", (spec, subject) -> { });
    }

    @AfterEach
    void tearDown() {
        ProgressionRuntime.resetForTests();
    }

    @Nonnull
    private QuestEngine engine(@Nonnull Quest quest) {
        QuestEngine engine = QuestEngine.builder()
                .store(store)
                .rewardKinds(rewardKinds)
                .nativeEvents(false)
                .warn(message -> { })
                .build();
        engine.setQuests(List.of(quest));
        return engine;
    }

    /** One objective, and something to hand over when it is finished. */
    @Nonnull
    private static Quest.Builder quest(@Nonnull String id) {
        return bareQuest(id).reward(RewardSpec.of("NOTE", "text", "paid"));
    }

    /** The same, with nothing to pay out at all. */
    @Nonnull
    private static Quest.Builder bareQuest(@Nonnull String id) {
        return Quest.builder(id)
                .objective(ObjectiveDef.builder("kills", "KILL_ENTITY")
                        .target("Wolf").matchMode(MatchMode.EXACT).amount(1).build());
    }

    @Test
    void clearQuest_reportsTheReArmItself() {
        Quest q = quest("q_rearm").build();
        QuestEngine engine = engine(q);
        engine.accept(player, q);
        store.reset();

        engine.clearQuest(player, "q_rearm");

        assertTrue(store.dirtyCount > 0,
                "a re-arm reaching the engine from outside is a saved write like any other");
    }

    @Test
    void markCompleted_reportsItself() {
        Quest q = quest("q_done").build();
        QuestEngine engine = engine(q);
        engine.accept(player, q);
        store.reset();

        engine.markCompleted(player, q);

        assertTrue(store.dirtyCount > 0);
    }

    @Test
    void markUnclaimed_reportsItself() {
        Quest q = quest("q_parked").autoClaim(false).build();
        QuestEngine engine = engine(q);
        engine.accept(player, q);
        store.reset();

        engine.markUnclaimed(player, q);

        assertTrue(store.dirtyCount > 0);
    }

    @Test
    void aQuestThatPaysOutTheMomentItFinishes_commitsAtOnce() {
        Quest q = quest("q_auto").build();
        QuestEngine engine = engine(q);
        engine.accept(player, q);
        store.reset();

        engine.dispatch(player, "KILL_ENTITY", "Wolf", null, 1);

        assertTrue(store.dirtyCount > 0, "the completion is a change");
        assertEquals(1, store.flushCount,
                "the auto-claim path pays the reward, so it is a boundary like a hand-in");
    }

    @Test
    void aQuestParkedForCollection_reportsTheChangeButDoesNotCommitEarly() {
        Quest q = quest("q_park").autoClaim(false).build();
        QuestEngine engine = engine(q);
        engine.accept(player, q);
        store.reset();

        engine.dispatch(player, "KILL_ENTITY", "Wolf", null, 1);

        assertTrue(store.dirtyCount > 0, "parking is still a change");
        assertEquals(0, store.flushCount, "nothing was paid yet, so there is nothing to commit early");

        engine.claim(player, q);

        assertEquals(1, store.flushCount, "collecting it is the boundary");
    }

    @Test
    void aQuestWithNothingToPay_reportsTheChangeButDoesNotCommit() {
        Quest q = bareQuest("q_free").build();
        QuestEngine engine = engine(q);
        engine.accept(player, q);
        store.reset();

        engine.dispatch(player, "KILL_ENTITY", "Wolf", null, 1);

        assertTrue(store.dirtyCount > 0, "finishing is still a change");
        assertEquals(0, store.flushCount,
                "nothing changed hands, so a crash in the next second costs the player nothing"
                        + " a batch would not have saved anyway");
    }

    @Test
    void aForcedCompletionThatPaid_commitsExactlyOnce() {
        Quest q = quest("q_forced").build();
        QuestEngine engine = engine(q);
        engine.accept(player, q);
        store.reset();

        assertTrue(engine.forceComplete(player, q));

        assertEquals(1, store.flushCount, "an administrator's close-out paid, so it commits");
    }

    @Test
    void aForcedCompletionWithNothingToPay_doesNotCommit() {
        Quest q = bareQuest("q_forced_free").build();
        QuestEngine engine = engine(q);
        engine.accept(player, q);
        store.reset();

        assertTrue(engine.forceComplete(player, q));

        assertTrue(store.dirtyCount > 0, "closing it out is a change");
        assertEquals(0, store.flushCount, "on the same rule as the auto-claim path");
    }

    @Test
    void aSelfHealReArmingARepeatable_reportsButNeverCommits() {
        Quest q = quest("q_daily").repeat(Quest.Repeat.every(0L)).build();
        QuestEngine engine = engine(q);
        engine.accept(player, q);
        engine.forceComplete(player, q);
        store.reset();

        assertTrue(engine.selfHeal(player) > 0, "the repeatable is off cooldown, so it re-arms");

        assertTrue(store.dirtyCount > 0, "a re-arm is a saved write");
        assertEquals(0, store.flushCount,
                "a maintenance sweep must never commit, however many entries it touches");
    }

    @Test
    void theRuntimeForwarder_reportsBothToWhicheverStoreIsRegistered() {
        ProgressionRuntime.resetForTests();
        ProgressionRuntime.registrar("testmod").questStore(store);
        QuestProgressStore forwarder = ProgressionRuntime.quests().store();
        store.reset();

        forwarder.markDirty(player);
        forwarder.flush(player);

        assertEquals(1, store.dirtyCount, "the forwarder is a delegation, not a swallow");
        assertEquals(1, store.flushCount);
    }

    /** An in-memory store that also counts what the engine told it. */
    private static final class RecordingQuestStore implements QuestProgressStore {

        private final InMemoryQuestProgressStore delegate = new InMemoryQuestProgressStore();
        private int dirtyCount;
        private int flushCount;

        private void reset() {
            dirtyCount = 0;
            flushCount = 0;
        }

        @Override
        public void markDirty(@Nonnull Subject subject) {
            dirtyCount++;
        }

        @Override
        public void flush(@Nonnull Subject subject) {
            flushCount++;
        }

        @Nonnull
        @Override
        public QuestStatus status(@Nonnull Subject subject, @Nonnull String questId) {
            return delegate.status(subject, questId);
        }

        @Override
        public void setStatus(@Nonnull Subject subject, @Nonnull String questId,
                @Nonnull QuestStatus status) {
            delegate.setStatus(subject, questId, status);
        }

        @Nonnull
        @Override
        public String progressPayload(@Nonnull Subject subject, @Nonnull String questId) {
            return delegate.progressPayload(subject, questId);
        }

        @Override
        public void putProgressPayload(@Nonnull Subject subject, @Nonnull String questId,
                @Nonnull String payload) {
            delegate.putProgressPayload(subject, questId, payload);
        }

        @Override
        public long cooldownStamp(@Nonnull Subject subject, @Nonnull String questId) {
            return delegate.cooldownStamp(subject, questId);
        }

        @Override
        public void setCooldownStamp(@Nonnull Subject subject, @Nonnull String questId, long epochMs) {
            delegate.setCooldownStamp(subject, questId, epochMs);
        }

        @Nonnull
        @Override
        public Set<String> knownQuestIds(@Nonnull Subject subject) {
            return delegate.knownQuestIds(subject);
        }

        @Override
        public void clearQuest(@Nonnull Subject subject, @Nonnull String questId) {
            delegate.clearQuest(subject, questId);
        }

        @Nonnull
        @Override
        public Map<String, Long> trackedPins(@Nonnull Subject subject) {
            return delegate.trackedPins(subject);
        }

        @Override
        public void setTrackedPin(@Nonnull Subject subject, @Nonnull String questId, long pinnedAtMs) {
            delegate.setTrackedPin(subject, questId, pinnedAtMs);
        }

        @Override
        public boolean clearTrackedPin(@Nonnull Subject subject, @Nonnull String questId) {
            return delegate.clearTrackedPin(subject, questId);
        }

        @Nonnull
        @Override
        public CompletionRecord completions(@Nonnull Subject subject, @Nonnull String questId) {
            return delegate.completions(subject, questId);
        }

        @Override
        public void setCompletions(@Nonnull Subject subject, @Nonnull String questId,
                @Nonnull CompletionRecord record) {
            delegate.setCompletions(subject, questId, record);
        }

        @Override
        public boolean recordsCompletions() {
            return delegate.recordsCompletions();
        }
    }
}
