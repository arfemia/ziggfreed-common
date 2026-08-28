package com.ziggfreed.common.achievement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.progress.MatchMode;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.ObjectiveKind;
import com.ziggfreed.common.progress.ObjectiveKindRegistry;
import com.ziggfreed.common.subject.Subject;

/**
 * The always-on lifecycle end to end: criteria matching in the lenient dialect, the earn/collect
 * state machine, points and milestones, meta cascade, and pins.
 *
 * <p>Native events are OFF throughout - there is no event bus in a unit JVM, and what is under test
 * is the mechanics rather than the outbound courtesy.
 */
class AchievementEngineTest {

    private static final Subject ALICE = new Subject(new UUID(0, 1), "Alice", null);

    private final AtomicLong clock = new AtomicLong(1_000L);
    private final List<String> paid = new ArrayList<>();

    private ObjectiveKindRegistry kinds() {
        ObjectiveKindRegistry kinds = new ObjectiveKindRegistry();
        kinds.register(null, ObjectiveKind.of("BREAK_BLOCK"));
        kinds.register(null, ObjectiveKind.valueBased("REACH_DEPTH"));
        return kinds;
    }

    private RewardKindRegistry rewards() {
        RewardKindRegistry rewards = new RewardKindRegistry();
        rewards.register("test:pay", (spec, subject) -> paid.add(spec.paramOr("Id", "?")));
        return rewards;
    }

    private AchievementEngine.Builder engine() {
        return AchievementEngine.builder()
                .objectiveKinds(kinds())
                .rewardKinds(rewards())
                .clock(clock::get)
                .nativeEvents(false);
    }

    private static ObjectiveDef criterion(int index, String kind, String target, long amount) {
        return ObjectiveDef.builder(String.valueOf(index), kind)
                .target(target)
                .matchMode(MatchMode.EXACT)
                .amount(amount)
                .build();
    }

    // ==================== Matching ====================

    @Test
    void criteriaMatchInTheLenientDialect() {
        AchievementEngine engine = engine().build();
        Achievement achievement = Achievement.builder("prospector")
                .criterion(criterion(0, "BREAK_BLOCK", "Copper_Ore", 3))
                .build();
        engine.setAchievements(List.of(achievement));

        engine.dispatch(ALICE, "BREAK_BLOCK", "copper_ore", null, 1L);
        assertEquals(1, engine.progressOf(ALICE, achievement, 0).current(),
                "matching compares targets without regard to case");

        engine.dispatch(ALICE, "BREAK_BLOCK", "Iron_Ore", null, 1L);
        assertEquals(1, engine.progressOf(ALICE, achievement, 0).current(),
                "a different target never counts");
    }

    @Test
    void anEmptyTargetMatchesEverything() {
        AchievementEngine engine = engine().build();
        Achievement achievement = Achievement.builder("industrious")
                .criterion(ObjectiveDef.builder("0", "BREAK_BLOCK").amount(3).build())
                .build();
        engine.setAchievements(List.of(achievement));

        engine.dispatch(ALICE, "BREAK_BLOCK", "Copper_Ore", null, 1L);
        engine.dispatch(ALICE, "BREAK_BLOCK", "Stone", null, 1L);
        assertEquals(2, engine.progressOf(ALICE, achievement, 0).current(),
                "an unstated target counts anything in the lenient dialect");
    }

    @Test
    void aValueBasedKindRaisesAHighWaterMarkRatherThanAccumulating() {
        AchievementEngine engine = engine().build();
        Achievement achievement = Achievement.builder("deep")
                .criterion(criterion(0, "REACH_DEPTH", "", 100))
                .build();
        engine.setAchievements(List.of(achievement));

        engine.dispatch(ALICE, "REACH_DEPTH", "", null, 60L);
        engine.dispatch(ALICE, "REACH_DEPTH", "", null, 40L);
        assertEquals(60, engine.progressOf(ALICE, achievement, 0).current(),
                "a run of 60 then 40 leaves a best at 60, not 100");
    }

    // ==================== Earning and collecting ====================

    @Test
    void everyCriterionMustBeMetBeforeItIsEarned() {
        AchievementEngine engine = engine().build();
        Achievement achievement = Achievement.builder("prospector")
                .criterion(criterion(0, "BREAK_BLOCK", "Copper_Ore", 2))
                .criterion(criterion(1, "BREAK_BLOCK", "Iron_Ore", 1))
                .build();
        engine.setAchievements(List.of(achievement));

        engine.dispatch(ALICE, "BREAK_BLOCK", "Copper_Ore", null, 2L);
        assertFalse(engine.isUnlocked(ALICE, "prospector"), "one criterion met is not the whole thing");
        assertEquals(new AchievementEngine.CriterionTally(1, 2), engine.tally(ALICE, achievement));

        engine.dispatch(ALICE, "BREAK_BLOCK", "Iron_Ore", null, 1L);
        assertTrue(engine.isUnlocked(ALICE, "prospector"));
    }

    @Test
    void withNothingToCollectItSettlesInOneStep() {
        AchievementEngine engine = engine().build();
        Achievement achievement = Achievement.builder("prospector")
                .criterion(criterion(0, "BREAK_BLOCK", "Copper_Ore", 1))
                .autoReward(RewardSpec.of("test:pay", "Id", "coin"))
                .build();
        engine.setAchievements(List.of(achievement));

        clock.set(4242L);
        engine.dispatch(ALICE, "BREAK_BLOCK", "Copper_Ore", null, 1L);

        assertEquals(AchievementStatus.CLAIMED, engine.status(ALICE, "prospector"),
                "nothing waiting means nothing to come back for");
        assertEquals(List.of("coin"), paid);
        assertEquals(4242L, engine.unlockedAt(ALICE, "prospector"));
    }

    @Test
    void somethingToCollectWaitsUntilItIsCollected() {
        AchievementEngine engine = engine().build();
        Achievement achievement = Achievement.builder("prospector")
                .criterion(criterion(0, "BREAK_BLOCK", "Copper_Ore", 1))
                .autoReward(RewardSpec.of("test:pay", "Id", "instant"))
                .claimReward(RewardSpec.of("test:pay", "Id", "later"))
                .build();
        engine.setAchievements(List.of(achievement));

        engine.dispatch(ALICE, "BREAK_BLOCK", "Copper_Ore", null, 1L);
        assertEquals(AchievementStatus.UNLOCKED, engine.status(ALICE, "prospector"));
        assertEquals(List.of("instant"), paid, "only the immediate reward has landed");

        assertTrue(engine.claim(ALICE, achievement));
        assertEquals(AchievementStatus.CLAIMED, engine.status(ALICE, "prospector"));
        assertEquals(List.of("instant", "later"), paid);

        assertFalse(engine.claim(ALICE, achievement), "collecting twice pays nothing twice");
        assertEquals(2, paid.size());
    }

    /**
     * Each moment carries the list ITS grant pays under {@code rewards}: the unlock the immediate
     * rewards, the collect the waiting ones - so an authored toast lists exactly what that moment
     * handed over.
     */
    @Test
    void theUnlockAndClaimMomentsCarryTheListTheirGrantPays() {
        Map<String, Map<String, Object>> byMoment = new LinkedHashMap<>();
        AchievementEngine engine = engine()
                .feedbackHook((momentId, subject, args) -> byMoment.put(momentId, args))
                .build();
        Achievement achievement = Achievement.builder("prospector")
                .criterion(criterion(0, "BREAK_BLOCK", "Copper_Ore", 1))
                .autoReward(RewardSpec.of("test:pay", "Id", "instant"))
                .claimReward(RewardSpec.of("test:pay", "Id", "later"))
                .build();
        engine.setAchievements(List.of(achievement));

        engine.dispatch(ALICE, "BREAK_BLOCK", "Copper_Ore", null, 1L);
        assertEquals(achievement.autoRewards(),
                byMoment.get("Achievement_Unlocked").get("rewards"),
                "the unlock moment carries what the earn pays on the spot");

        engine.claim(ALICE, achievement);
        assertEquals(achievement.claimRewards(),
                byMoment.get("Achievement_Claimed").get("rewards"),
                "the collect moment carries what the collect pays");
    }

    @Test
    void aGateThatRefusesTheEarnLeavesTheCriteriaMet() {
        List<String> refused = new ArrayList<>(List.of("prospector"));
        AchievementEngine engine = engine()
                .gates(new AchievementGates() {
                    @Override
                    public boolean canUnlock(Subject subject, Achievement achievement) {
                        return !refused.contains(achievement.id());
                    }
                })
                .build();
        Achievement achievement = Achievement.builder("prospector")
                .criterion(criterion(0, "BREAK_BLOCK", "Copper_Ore", 1))
                .build();
        engine.setAchievements(List.of(achievement));

        engine.dispatch(ALICE, "BREAK_BLOCK", "Copper_Ore", null, 1L);
        assertFalse(engine.isUnlocked(ALICE, "prospector"));
        assertTrue(engine.allCriteriaComplete(ALICE, achievement),
                "a refusal must lose nothing: the criteria stay met so the decision can be revisited");

        refused.clear();
        assertEquals(1, engine.selfHeal(ALICE), "and self-heal is what revisits it");
        assertTrue(engine.isUnlocked(ALICE, "prospector"));
    }

    @Test
    void aGateThatFreezesProgressLeavesWhatIsAlreadyRecorded() {
        List<String> frozen = new ArrayList<>();
        AchievementEngine engine = engine()
                .gates(new AchievementGates() {
                    @Override
                    public boolean canProgress(Subject subject, Achievement achievement) {
                        return !frozen.contains(achievement.id());
                    }
                })
                .build();
        Achievement achievement = Achievement.builder("prospector")
                .criterion(criterion(0, "BREAK_BLOCK", "Copper_Ore", 5))
                .build();
        engine.setAchievements(List.of(achievement));

        engine.dispatch(ALICE, "BREAK_BLOCK", "Copper_Ore", null, 2L);
        frozen.add("prospector");
        engine.dispatch(ALICE, "BREAK_BLOCK", "Copper_Ore", null, 2L);

        assertEquals(2, engine.progressOf(ALICE, achievement, 0).current(),
                "freezing stops the counter without touching what is already there");
    }

    @Test
    void anAchievementOutOfCirculationNeitherProgressesNorIsEarned() {
        AchievementEngine engine = engine().build();
        Achievement achievement = Achievement.builder("retired")
                .criterion(criterion(0, "BREAK_BLOCK", "Copper_Ore", 1))
                .available(false)
                .build();
        engine.setAchievements(List.of(achievement));

        engine.dispatch(ALICE, "BREAK_BLOCK", "Copper_Ore", null, 1L);
        assertFalse(engine.isUnlocked(ALICE, "retired"));
        assertEquals(0, engine.progressOf(ALICE, achievement, 0).current());
    }

    @Test
    void revokingTakesItBackAndTakesTheCapstoneWithIt() {
        AchievementEngine engine = engine().build();
        Achievement child = Achievement.builder("first")
                .criterion(criterion(0, "BREAK_BLOCK", "Copper_Ore", 1))
                .build();
        Achievement capstone = Achievement.builder("capstone").metaChildren(List.of("first")).build();
        engine.setAchievements(List.of(child, capstone));

        engine.dispatch(ALICE, "BREAK_BLOCK", "Copper_Ore", null, 1L);
        assertTrue(engine.isUnlocked(ALICE, "capstone"));

        assertTrue(engine.revoke(ALICE, "first"));
        assertFalse(engine.isUnlocked(ALICE, "first"));
        assertFalse(engine.isUnlocked(ALICE, "capstone"),
                "a capstone standing on a revoked achievement cannot keep standing");
    }

    @Test
    void resetAllWipesEveryRecordAndEveryMilestoneAndReportsHowMany() {
        AchievementEngine engine = engine()
                .milestone(AchievementMilestone.auto(25, List.of(RewardSpec.of("test:pay", "Id", "bronze"))))
                .build();
        Achievement earned = Achievement.builder("earned").points(30)
                .criterion(criterion(0, "BREAK_BLOCK", "A", 1)).build();
        Achievement halfway = Achievement.builder("halfway")
                .criterion(criterion(0, "BREAK_BLOCK", "B", 2)).build();
        engine.setAchievements(List.of(earned, halfway));
        engine.dispatch(ALICE, "BREAK_BLOCK", "A", null, 1L);
        engine.dispatch(ALICE, "BREAK_BLOCK", "B", null, 1L);
        assertTrue(engine.pin(ALICE, "halfway"));
        assertTrue(engine.isUnlocked(ALICE, "earned"));
        assertEquals(AchievementStatus.CLAIMED, engine.milestoneStatus(ALICE, 25));

        assertEquals(3, engine.resetAll(ALICE), "two achievement ids and one milestone had a record");

        assertFalse(engine.isUnlocked(ALICE, "earned"), "the earn is gone");
        assertEquals(0L, engine.unlockedAt(ALICE, "earned"), "and so is the instant it was earned");
        assertEquals(0, engine.progressOf(ALICE, halfway, 0).current(), "partial progress is gone");
        assertTrue(engine.pinned(ALICE).isEmpty(), "and so is the pin");
        assertEquals(AchievementStatus.LOCKED, engine.milestoneStatus(ALICE, 25),
                "the milestone reads as never reached");
        assertEquals(0, engine.resetAll(ALICE), "a second wipe finds nothing left to wipe");
    }

    // ==================== Meta cascade ====================

    @Test
    void aCapstoneEarnsItselfWhenItsChildrenAreDoneAndCascadesUpwards() {
        AchievementEngine engine = engine().build();
        Achievement one = Achievement.builder("one").criterion(criterion(0, "BREAK_BLOCK", "A", 1)).build();
        Achievement two = Achievement.builder("two").criterion(criterion(0, "BREAK_BLOCK", "B", 1)).build();
        Achievement capstone = Achievement.builder("capstone").metaChildren(List.of("one", "two")).build();
        Achievement grand = Achievement.builder("grand").metaChildren(List.of("capstone")).build();
        engine.setAchievements(List.of(one, two, capstone, grand));

        engine.dispatch(ALICE, "BREAK_BLOCK", "A", null, 1L);
        assertFalse(engine.isUnlocked(ALICE, "capstone"), "one child of two is not the set");

        engine.dispatch(ALICE, "BREAK_BLOCK", "B", null, 1L);
        assertTrue(engine.isUnlocked(ALICE, "capstone"));
        assertTrue(engine.isUnlocked(ALICE, "grand"), "a capstone over a capstone earns itself too");
        assertEquals(new AchievementEngine.CriterionTally(2, 2), engine.tally(ALICE, capstone));
    }

    // ==================== Points and milestones ====================

    @Test
    void onlyEarnedAchievementsWhosePointsCountAddToATotal() {
        AchievementEngine engine = engine().build();
        Achievement worth20 = Achievement.builder("a").points(20)
                .criterion(criterion(0, "BREAK_BLOCK", "A", 1)).build();
        Achievement worth5 = Achievement.builder("b").points(5)
                .criterion(criterion(0, "BREAK_BLOCK", "B", 1)).build();
        Achievement retired = Achievement.builder("c").points(50).countsTowardTotal(false)
                .criterion(criterion(0, "BREAK_BLOCK", "C", 1)).build();
        engine.setAchievements(List.of(worth20, worth5, retired));

        assertEquals(0, engine.points(ALICE));
        assertEquals(25, engine.pointsAvailable(ALICE), "what does not count is not on offer either");

        engine.dispatch(ALICE, "BREAK_BLOCK", "A", null, 1L);
        engine.dispatch(ALICE, "BREAK_BLOCK", "C", null, 1L);
        assertEquals(20, engine.points(ALICE), "the retired one is earned but adds nothing");
        assertEquals(5, engine.pointsAvailable(ALICE));
    }

    @Test
    void aMilestoneIsReachedOnceAndOnlyOnce() {
        AchievementEngine engine = engine()
                .milestone(AchievementMilestone.auto(25, List.of(RewardSpec.of("test:pay", "Id", "bronze"))))
                .milestone(AchievementMilestone.claimable(50,
                        List.of(RewardSpec.of("test:pay", "Id", "silver"))))
                .build();
        Achievement worth30 = Achievement.builder("a").points(30)
                .criterion(criterion(0, "BREAK_BLOCK", "A", 1)).build();
        Achievement worth30b = Achievement.builder("b").points(30)
                .criterion(criterion(0, "BREAK_BLOCK", "B", 1)).build();
        engine.setAchievements(List.of(worth30, worth30b));

        engine.dispatch(ALICE, "BREAK_BLOCK", "A", null, 1L);
        assertEquals(AchievementStatus.CLAIMED, engine.milestoneStatus(ALICE, 25),
                "a milestone with nothing to collect settles at once");
        assertEquals(AchievementStatus.LOCKED, engine.milestoneStatus(ALICE, 50));
        assertEquals(List.of("bronze"), paid);

        engine.dispatch(ALICE, "BREAK_BLOCK", "B", null, 1L);
        assertEquals(AchievementStatus.UNLOCKED, engine.milestoneStatus(ALICE, 50),
                "a milestone with something to collect waits");
        assertEquals(List.of("bronze"), paid, "and pays nothing until it is collected");

        assertTrue(engine.claimMilestone(ALICE, 50));
        assertEquals(List.of("bronze", "silver"), paid);
        assertFalse(engine.claimMilestone(ALICE, 50), "collecting twice pays nothing twice");

        assertEquals(0, engine.checkMilestones(ALICE), "re-checking reaches nothing again");
    }

    // ==================== Pins ====================

    @Test
    void pinsAreCappedOldestFirstAndReclaimedWhenTheyAreEarned() {
        AchievementEngine engine = engine().maxPinned(2).build();
        Achievement one = Achievement.builder("one").criterion(criterion(0, "BREAK_BLOCK", "A", 1)).build();
        Achievement two = Achievement.builder("two").criterion(criterion(0, "BREAK_BLOCK", "B", 1)).build();
        Achievement three = Achievement.builder("three").criterion(criterion(0, "BREAK_BLOCK", "C", 1)).build();
        engine.setAchievements(List.of(one, two, three));

        clock.set(10L);
        assertTrue(engine.pin(ALICE, "one"));
        clock.set(20L);
        assertTrue(engine.pin(ALICE, "two"));
        assertFalse(engine.pin(ALICE, "three"), "the cap holds");
        assertEquals(List.of("one", "two"), engine.pinned(ALICE), "oldest pin first");

        assertFalse(engine.pin(ALICE, "nothing_like_it"), "an unknown id cannot be pinned");

        engine.dispatch(ALICE, "BREAK_BLOCK", "A", null, 1L);
        assertEquals(List.of("two"), engine.pinned(ALICE), "earning it gives its slot back");
        clock.set(30L);
        assertTrue(engine.pin(ALICE, "three"));

        assertFalse(engine.pin(ALICE, "one"), "something already earned is nothing to work toward");
    }

    // ==================== Keyed criteria ====================

    @Test
    void criterionProgressIsKeyedByIdSoReorderingNeverMovesIt() {
        AchievementEngine engine = engine().build();
        Achievement authored = Achievement.builder("prospector")
                .criterion(criterion(0, "BREAK_BLOCK", "Copper_Ore", 5))
                .criterion(criterion(1, "BREAK_BLOCK", "Iron_Ore", 5))
                .build();
        engine.setAchievements(List.of(authored));

        engine.dispatch(ALICE, "BREAK_BLOCK", "Copper_Ore", null, 3L);
        assertEquals(3, engine.progressOf(ALICE, authored, 0).current());
        assertEquals(0, engine.progressOf(ALICE, authored, 1).current());

        // Re-authored with the same two criteria SWAPPED. Progress is stored under each
        // criterion's ID, so the copper tally follows the copper criterion to its new position
        // rather than landing on whatever now sits first - the whole point of keyed criteria.
        Achievement reordered = Achievement.builder("prospector")
                .criterion(criterion(1, "BREAK_BLOCK", "Iron_Ore", 5))
                .criterion(criterion(0, "BREAK_BLOCK", "Copper_Ore", 5))
                .build();
        engine.setAchievements(List.of(reordered));

        assertEquals(0, engine.progressOf(ALICE, reordered, 0).current(),
                "the iron criterion keeps its own (empty) tally wherever it sits");
        assertEquals(3, engine.progressOf(ALICE, reordered, 1).current(),
                "the copper criterion carries its progress with it");
    }

    // ==================== Maintenance ====================

    @Test
    void selfHealIsIdempotentAndNonDestructive() {
        AchievementEngine engine = engine().build();
        Achievement achievement = Achievement.builder("prospector")
                .criterion(criterion(0, "BREAK_BLOCK", "Copper_Ore", 1))
                .build();
        engine.setAchievements(List.of(achievement));

        engine.dispatch(ALICE, "BREAK_BLOCK", "Copper_Ore", null, 1L);
        assertEquals(0, engine.selfHeal(ALICE), "nothing to heal when everything is settled");
        assertTrue(engine.isUnlocked(ALICE, "prospector"), "and nothing already earned is disturbed");
    }
}
