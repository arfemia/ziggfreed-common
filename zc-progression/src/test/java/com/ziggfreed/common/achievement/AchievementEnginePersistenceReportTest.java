package com.ziggfreed.common.achievement;

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
import com.ziggfreed.common.progress.ObjectiveKind;
import com.ziggfreed.common.progress.ObjectiveKindRegistry;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.subject.Subject;

/**
 * The achievement peer of the quest engine's persistence-report test. Every mutating path REPORTS
 * the change ({@code markDirty}); exactly two COMMIT it ({@code flush}), and both are the subject
 * collecting something.
 *
 * <p>The commit half is asserted with exact counts rather than "at least one" on purpose, because
 * the failure it guards is a flood rather than a silence: earning is something the engine decides
 * and it arrives in BULK - a self-heal walks the whole catalogue on login, one earn cascades through
 * a run of meta achievements, and every earn re-checks the milestones. A commit at any of those
 * turns one login into a database write per achievement the subject already had, which is invisible
 * on the default in-world store and expensive on every other kind.
 *
 * <p>A consumer's persistence backend hears about a change only because the engine said so, so what
 * the engine says is worth asserting rather than reviewing.
 */
class AchievementEnginePersistenceReportTest {

    private static final Subject ALICE = new Subject(new UUID(0, 1), "Alice", null);

    private RecordingAchievementStore store;

    @BeforeEach
    void setUp() {
        store = new RecordingAchievementStore();
    }

    @AfterEach
    void tearDown() {
        ProgressionRuntime.resetForTests();
    }

    @Nonnull
    private AchievementEngine.Builder engine() {
        ObjectiveKindRegistry kinds = new ObjectiveKindRegistry();
        kinds.register(null, ObjectiveKind.of("BREAK_BLOCK"));
        RewardKindRegistry rewards = new RewardKindRegistry();
        rewards.register("test:pay", (spec, subject) -> { });
        return AchievementEngine.builder()
                .store(store)
                .objectiveKinds(kinds)
                .rewardKinds(rewards)
                .nativeEvents(false);
    }

    /** One criterion, and something that lands the moment it is earned. */
    @Nonnull
    private static Achievement.Builder achievement(@Nonnull String id) {
        return bareAchievement(id).autoReward(RewardSpec.of("test:pay", "Id", id));
    }

    /** The same, with nothing to pay out at all. */
    @Nonnull
    private static Achievement.Builder bareAchievement(@Nonnull String id) {
        return Achievement.builder(id)
                .criterion(ObjectiveDef.builder("0", "BREAK_BLOCK")
                        .target("Copper_Ore").matchMode(MatchMode.EXACT).amount(1).build());
    }

    // ==================== what does NOT commit ====================

    @Test
    void anUnlockWithNothingToPayReportsTheChangeButDoesNotCommit() {
        Achievement a = bareAchievement("quiet").build();
        AchievementEngine engine = engine().build();
        engine.setAchievements(List.of(a));
        store.reset();

        assertTrue(engine.unlock(ALICE, a));

        assertTrue(store.dirtyCount > 0, "earning is a change");
        assertEquals(0, store.flushCount, "nothing changed hands, so there is nothing to commit");
    }

    @Test
    void anUnlockThatPaidDoesNotCommitEither() {
        Achievement a = achievement("prospector").build();
        AchievementEngine engine = engine().build();
        engine.setAchievements(List.of(a));
        store.reset();

        assertTrue(engine.unlock(ALICE, a));

        assertTrue(store.dirtyCount > 0, "earning is a change");
        assertEquals(0, store.flushCount,
                "earning is the engine's decision, not the subject's, and it arrives in bulk -"
                        + " collecting is the boundary");
    }

    @Test
    void aMilestoneReachedReportsTheChangeButDoesNotCommit() {
        Achievement a = achievement("prospector").points(10).build();
        AchievementEngine engine = engine().build();
        engine.setAchievements(List.of(a));
        engine.setMilestones(List.of(AchievementMilestone.auto(10,
                List.of(RewardSpec.of("test:pay", "Id", "milestone")))));
        store.reset();

        engine.unlock(ALICE, a);

        assertEquals(AchievementStatus.CLAIMED, engine.milestoneStatus(ALICE, 10));
        assertTrue(store.dirtyCount > 0, "reaching a milestone is a change");
        assertEquals(0, store.flushCount,
                "the milestone check is idempotent maintenance that runs on login and after every"
                        + " earn, so a commit here rides along with each of those");
    }

    @Test
    void aSelfHealEarningManyAchievementsCommitsNothingAtAll() {
        Gate gate = new Gate();
        AchievementEngine engine = engine().gates(gate).build();
        engine.setAchievements(List.of(
                achievement("a").build(), achievement("b").build(), achievement("c").build(),
                achievement("d").build(), achievement("e").build()));

        // Every criterion is met while the gate refuses, which leaves five achievements earnable and
        // none earned - the exact state a login self-heal walks into.
        engine.dispatch(ALICE, "BREAK_BLOCK", "Copper_Ore", null, 1);
        assertEquals(AchievementStatus.LOCKED, engine.status(ALICE, "a"),
                "a refusal must leave the criteria met rather than losing them");
        gate.open = true;
        store.reset();

        assertEquals(5, engine.selfHeal(ALICE), "the sweep earns all five");

        assertTrue(store.dirtyCount > 0, "the sweep changed things");
        assertEquals(0, store.flushCount,
                "a login must never cost one write per achievement the subject already had");
    }

    // ==================== what DOES commit, and exactly once ====================

    @Test
    void collectingAnAchievementCommitsExactlyOnce() {
        Achievement a = achievement("prospector")
                .claimReward(RewardSpec.of("test:pay", "Id", "collected")).build();
        AchievementEngine engine = engine().build();
        engine.setAchievements(List.of(a));
        engine.unlock(ALICE, a);
        assertEquals(AchievementStatus.UNLOCKED, engine.status(ALICE, "prospector"));
        store.reset();

        assertTrue(engine.claim(ALICE, a));

        assertTrue(store.dirtyCount > 0, "collecting is a change");
        assertEquals(1, store.flushCount, "collecting is the boundary, and it is reached once");
    }

    @Test
    void collectingAMilestoneCommitsExactlyOnce() {
        Achievement a = achievement("prospector").points(10).build();
        AchievementEngine engine = engine().build();
        engine.setAchievements(List.of(a));
        engine.setMilestones(List.of(AchievementMilestone.claimable(10,
                List.of(RewardSpec.of("test:pay", "Id", "milestone")))));
        engine.unlock(ALICE, a);
        assertEquals(AchievementStatus.UNLOCKED, engine.milestoneStatus(ALICE, 10));
        store.reset();

        assertTrue(engine.claimMilestone(ALICE, 10));

        assertEquals(1, store.flushCount, "collecting a milestone is the second boundary");
    }

    // ==================== the forwarder door ====================

    @Test
    void theRuntimeForwarderReportsBothToWhicheverStoreIsRegistered() {
        ProgressionRuntime.resetForTests();
        ProgressionRuntime.registrar("testmod").achievementStore(store);
        AchievementProgressStore forwarder = ProgressionRuntime.achievements().store();
        store.reset();

        forwarder.markDirty(ALICE);
        forwarder.flush(ALICE);

        assertEquals(1, store.dirtyCount, "the forwarder is a delegation, not a swallow");
        assertEquals(1, store.flushCount);
    }

    /** A gate that refuses to let anything be earned until it is opened. */
    private static final class Gate implements AchievementGates {

        private boolean open;

        @Override
        public boolean canUnlock(@Nonnull Subject subject, @Nonnull Achievement achievement,
                @Nonnull UnlockOccasion occasion) {
            return open;
        }
    }

    /** An in-memory store that also counts what the engine told it. */
    private static final class RecordingAchievementStore implements AchievementProgressStore {

        private final InMemoryAchievementProgressStore delegate = new InMemoryAchievementProgressStore();
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

        @Override
        public long progress(@Nonnull Subject subject, @Nonnull String key) {
            return delegate.progress(subject, key);
        }

        @Override
        public void putProgress(@Nonnull Subject subject, @Nonnull String key, long value) {
            delegate.putProgress(subject, key, value);
        }

        @Nonnull
        @Override
        public Set<String> progressKeys(@Nonnull Subject subject) {
            return delegate.progressKeys(subject);
        }

        @Nonnull
        @Override
        public AchievementStatus status(@Nonnull Subject subject, @Nonnull String achievementId) {
            return delegate.status(subject, achievementId);
        }

        @Override
        public void setStatus(@Nonnull Subject subject, @Nonnull String achievementId,
                @Nonnull AchievementStatus status) {
            delegate.setStatus(subject, achievementId, status);
        }

        @Nonnull
        @Override
        public Set<String> knownAchievementIds(@Nonnull Subject subject) {
            return delegate.knownAchievementIds(subject);
        }

        @Override
        public long unlockedAt(@Nonnull Subject subject, @Nonnull String achievementId) {
            return delegate.unlockedAt(subject, achievementId);
        }

        @Override
        public void setUnlockedAt(@Nonnull Subject subject, @Nonnull String achievementId,
                long epochMs) {
            delegate.setUnlockedAt(subject, achievementId, epochMs);
        }

        @Nonnull
        @Override
        public AchievementStatus milestoneStatus(@Nonnull Subject subject, int threshold) {
            return delegate.milestoneStatus(subject, threshold);
        }

        @Override
        public void setMilestoneStatus(@Nonnull Subject subject, int threshold,
                @Nonnull AchievementStatus status) {
            delegate.setMilestoneStatus(subject, threshold, status);
        }

        @Nonnull
        @Override
        public Set<Integer> knownMilestones(@Nonnull Subject subject) {
            return delegate.knownMilestones(subject);
        }

        @Nonnull
        @Override
        public Map<String, Long> pins(@Nonnull Subject subject) {
            return delegate.pins(subject);
        }

        @Override
        public void setPin(@Nonnull Subject subject, @Nonnull String achievementId, long pinnedAtMs) {
            delegate.setPin(subject, achievementId, pinnedAtMs);
        }

        @Override
        public boolean clearPin(@Nonnull Subject subject, @Nonnull String achievementId) {
            return delegate.clearPin(subject, achievementId);
        }
    }
}
