package com.ziggfreed.common.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.AchievementEngine;
import com.ziggfreed.common.achievement.FirstClaimStore;
import com.ziggfreed.common.achievement.FirstClaims;
import com.ziggfreed.common.achievement.InMemoryAchievementProgressStore;
import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.factor.FactorRegistry;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.gate.GateEvaluator;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.subject.Subject;

/**
 * The ONE gate both progression engines share, answering off the runtime object.
 *
 * <p>Each case here was a way the split could look finished and not be. A gate that refused content
 * it had nothing to say about would lock every other mod's quests on a shared runtime; a gate that
 * only understood one authoring layer would leave the other ungated; an achievement side with no
 * gate at all would leave every achievement on the server open; and a server-first arbitrated
 * without a store would hand the same one-off to everybody who finished it.
 */
class RequiresGatesTest {

    private static final Subject PLAYER = Subject.of(UUID.randomUUID(), "tester");

    private FactorRegistry factors;
    private GateEvaluator evaluator;
    private RequiresGates gates;

    @BeforeEach
    void setUp() {
        FirstClaims.resetForTests();
        ProgressionRuntime.resetForTests();
        factors = new FactorRegistry("test");
        factors.register("yourmod:rank", "test", ctx -> Double.valueOf(1.0));
        evaluator = GateEvaluator.builder().factors(factors).build();
        gates = RequiresGates.of(evaluator);
    }

    @AfterEach
    void tearDown() {
        FirstClaims.resetForTests();
        ProgressionRuntime.resetForTests();
    }

    private static GateSpec rankAtLeast(int min) {
        return GateSpec.of(new FactorCondition[] {
                FactorCondition.of("yourmod:rank", null, Double.valueOf(min), null)},
                null, null, null, null, null, null);
    }

    // ==================== fail open ====================

    /**
     * Under one shared runtime every registered gate is asked about every piece of content, whoever
     * authored it. Content that asks for nothing is therefore not this gate's to refuse - refusing
     * would lock every other mod's quests the moment this one was installed, with a reason nobody
     * could trace back to it.
     */
    @Test
    void contentThatAsksForNothingIsNotThisGatesToRefuse() {
        Quest open = Quest.builder("somebody_elses_quest").build();

        assertNull(gates.firstFailure(PLAYER, open),
                "a quest carrying no requirement block has nothing for this gate to say");
        List<String> reasons = new ArrayList<>();
        assertTrue(gates.accepts(PLAYER, open, reasons));
        assertTrue(reasons.isEmpty(), "and it must not add a reason it cannot justify either");
        assertTrue(gates.prerequisitesMet(PLAYER, open));

        Achievement openAchievement = Achievement.builder("somebody_elses_achievement").build();
        assertTrue(gates.canProgress(PLAYER, openAchievement));
    }

    // ==================== one gate, either authoring layer ====================

    /**
     * The whole reason the block rides the runtime OBJECT: a quest folded from the shared schema and
     * one folded from a consumer's own older format are the same object by the time anything gates
     * them, so one gate answers both and there is nothing to keep in step.
     */
    @Test
    void aQuestIsGatedTheSameWhicheverLayerFoldedIt() {
        Quest fromSharedSchema = Quest.builder("shared").requires(rankAtLeast(10)).build();
        Quest fromLegacyFormat = Quest.builder("legacy").requires(rankAtLeast(10)).build();

        assertEquals(GateEvaluator.REASON_FACTOR + "yourmod:rank",
                gates.firstFailure(PLAYER, fromSharedSchema));
        assertEquals(gates.firstFailure(PLAYER, fromSharedSchema),
                gates.firstFailure(PLAYER, fromLegacyFormat),
                "the gate reads the object, so which authoring layer built it cannot matter");
    }

    /**
     * The refusal carries the evaluator's own token rather than a flat "prerequisites", so a surface
     * can name the factor that shut the gate rather than saying only that something did.
     */
    @Test
    void aRefusalNamesWhatShutTheGate() {
        Quest gated = Quest.builder("gated").requires(rankAtLeast(10)).build();
        List<String> reasons = new ArrayList<>();

        assertFalse(gates.accepts(PLAYER, gated, reasons));
        assertEquals(List.of(GateEvaluator.REASON_FACTOR + "yourmod:rank"), reasons);
    }

    /** An achievement's block is read by the same gate, through the same evaluator. */
    @Test
    void anAchievementIsGatedByTheSameBlock() {
        Achievement gated = Achievement.builder("gated").requires(rankAtLeast(10)).build();
        assertFalse(gates.canProgress(PLAYER, gated));

        Achievement reachable = Achievement.builder("reachable").requires(rankAtLeast(1)).build();
        assertTrue(gates.canProgress(PLAYER, reachable));
    }

    /**
     * Accepting asks this gate ONE question, not two.
     *
     * <p>The engine wants both "is the player past what the quest asks for first" and "does the gate
     * let them take it", and for this gate they are the same block read the same way. Asking twice
     * would cost a second full evaluation for one decision on a path that runs per quest every time
     * a giver's list or a quest log renders, and every factor reading behind it - a saved record, a
     * component fetch - would be paid twice with it.
     */
    @Test
    void acceptingReadsTheRequirementBlockOnce() {
        AtomicInteger reads = new AtomicInteger();
        FactorRegistry counted = new FactorRegistry("test");
        counted.register("yourmod:rank", "test", ctx -> {
            reads.incrementAndGet();
            return Double.valueOf(1.0);
        });
        QuestEngine engine = QuestEngine.builder()
                .store(new InMemoryQuestProgressStore())
                .gates(RequiresGates.of(GateEvaluator.builder().factors(counted).build()))
                .nativeEvents(false)
                .build();
        Quest gated = Quest.builder("gated")
                .requires(rankAtLeast(10))
                .visibility(new Quest.Visibility(false, true))
                .objective(ObjectiveDef.builder("step", "PICKUP_ITEM").target("Ore").build())
                .build();
        engine.setQuests(List.of(gated));

        QuestEngine.AcceptCheck check = engine.canAccept(PLAYER, gated);

        assertEquals(1, reads.get(), "one decision, one reading of the block behind it");
        assertFalse(check.allowed());
        assertTrue(check.reasons().contains(QuestGates.REASON_PREREQUISITES),
                "a quest that requires its prerequisites still says so");
        assertTrue(check.reasons().contains(GateEvaluator.REASON_FACTOR + "yourmod:rank"),
                "and the specific token is still there beside it, so nothing was traded for the"
                        + " saving");
    }

    /**
     * The giver read is answered by the same block, its permission leaf included.
     *
     * <p>A quest an author put behind a permission is one they meant a player without it never to
     * be shown. The character who hands it out reads only the block, so a permission that never
     * reaches the block leaves that character offering the quest to exactly the players it was
     * written to keep it from, while every listing that reads the permission some other way
     * correctly withholds it - and both surfaces look right on their own.
     */
    @Test
    void theGiverReadHonoursAPermissionInTheBlock() {
        Quest gated = Quest.builder("q_permission_offer")
                .requires(GateSpec.of(null, "yourmod.secret", null, null, null, null, null))
                .visibility(new Quest.Visibility(true, true))
                .objective(ObjectiveDef.builder("step", "PICKUP_ITEM").target("Ore").build())
                .build();

        QuestEngine withoutIt = QuestEngine.builder()
                .store(new InMemoryQuestProgressStore())
                .gates(gates)
                .nativeEvents(false)
                .build();
        withoutIt.setQuests(List.of(gated));
        assertFalse(withoutIt.isOfferable(PLAYER, gated),
                "nothing answers the node for this subject, so the character holding the quest is"
                        + " not holding it out to them");

        FactorRegistry holder = new FactorRegistry("test");
        holder.register(GateEvaluator.PERMISSION_FACTOR, "test", ctx -> Double.valueOf(1.0));
        QuestEngine withIt = QuestEngine.builder()
                .store(new InMemoryQuestProgressStore())
                .gates(RequiresGates.of(GateEvaluator.builder().factors(holder).build()))
                .nativeEvents(false)
                .build();
        withIt.setQuests(List.of(gated));
        assertTrue(withIt.isOfferable(PLAYER, gated),
                "and a player who holds it is offered it, so the leaf gates rather than hides");
    }

    // ==================== rewards ====================

    /**
     * A payout with no player behind it never reaches an inventory, so it is never held back. A
     * false answer always names specific items that specifically will not fit, which is what makes
     * parking a quest for later an honest thing to do rather than a guess.
     */
    @Test
    void aPayoutWithNobodyToReceiveItIsNotParked() {
        Quest quest = Quest.builder("paid").build();
        assertTrue(gates.canReceiveRewards(PLAYER, quest));

        Achievement achievement = Achievement.builder("paid").build();
        assertTrue(gates.canReceiveRewards(PLAYER, achievement));
    }

    // ==================== server first ====================

    /** Exactly one subject wins, and everybody else is refused rather than quietly also earning it. */
    @Test
    void exactlyOneSubjectTakesAServerFirst() {
        Achievement first = Achievement.builder("first_to_100").serverFirst(true).build();
        Subject winner = Subject.of(UUID.randomUUID(), "winner");
        Subject loser = Subject.of(UUID.randomUUID(), "loser");

        assertTrue(gates.canUnlock(winner, first));
        assertFalse(gates.canUnlock(loser, first));
        assertTrue(gates.canUnlock(winner, first),
                "the winner asking again is still the winner, so a self-heal cannot lose it");
    }

    /** An ordinary achievement never touches the claim table at all. */
    @Test
    void anAchievementNobodyRacedForIsNotArbitrated() {
        AtomicInteger asked = new AtomicInteger();
        FirstClaims.install((id, subjectId, name) -> {
            asked.incrementAndGet();
            return true;
        });
        assertTrue(gates.canUnlock(PLAYER, Achievement.builder("ordinary").build()));
        assertEquals(0, asked.get());
    }

    /** The consumer's own durable table answers, which is the whole point of the seam. */
    @Test
    void aConsumersOwnClaimTableIsWhatDecides() {
        FirstClaimStore refuseEverything = (id, subjectId, name) -> false;
        FirstClaims.install(refuseEverything);
        assertFalse(FirstClaims.isDefault());

        assertFalse(gates.canUnlock(PLAYER, Achievement.builder("first").serverFirst(true).build()));
    }

    /** A subject nobody can identify must never take a claim only one player may ever hold. */
    @Test
    void anAnonymousSubjectNeverTakesAClaim() {
        Subject nobody = Subject.of(new UUID(0L, 0L), "");
        assertFalse(gates.canUnlock(nobody, Achievement.builder("first").serverFirst(true).build()));
        assertTrue(gates.canUnlock(PLAYER, Achievement.builder("first").serverFirst(true).build()),
                "and the claim is still there for a real player afterwards");
    }

    /**
     * A loss is announced as a MOMENT, so a consumer tells the player without the gate knowing any
     * words - and only the loser is announced, because the winner had nothing go wrong.
     */
    @Test
    void aLostRaceIsAnnouncedAsAMoment() {
        List<String> told = new ArrayList<>();
        ProgressionRuntime.registrar("test").feedbackHook((momentId, subject, args) ->
                told.add(momentId + ":" + subject.name() + ":" + args.get("achievement")));
        Achievement first = Achievement.builder("first").serverFirst(true).build();

        gates.canUnlock(Subject.of(UUID.randomUUID(), "winner"), first);
        gates.canUnlock(Subject.of(UUID.randomUUID(), "loser"), first);

        assertEquals(List.of("Achievement_Server_First_Lost:loser:first"), told);
    }

    // ==================== the live seams the consumer supplies ====================

    /**
     * The quest log cap is a NUMBER the consumer supplies and the refusal is the engine's, so an
     * owner raising the cap while the server is up is honoured without a catalogue rebuild.
     */
    @Test
    void theLogFullRefusalRunsAgainstTheLiveCap() {
        AtomicInteger cap = new AtomicInteger(1);
        InMemoryQuestProgressStore store = new InMemoryQuestProgressStore();
        QuestEngine engine = QuestEngine.builder()
                .store(store)
                .maxActive(cap::get)
                .nativeEvents(false)
                .build();
        Quest carried = Quest.builder("carried")
                .objective(ObjectiveDef.builder("step", "PICKUP_ITEM").target("Ore").build())
                .build();
        Quest wanted = Quest.builder("wanted")
                .objective(ObjectiveDef.builder("step", "PICKUP_ITEM").target("Ore").build())
                .build();
        engine.setQuests(List.of(carried, wanted));
        engine.accept(PLAYER, carried);

        assertTrue(engine.canAccept(PLAYER, wanted).reasons().contains(QuestGates.REASON_LOG_FULL),
                "at the cap, the engine's own refusal applies");

        cap.set(5);
        assertTrue(engine.canAccept(PLAYER, wanted).allowed(),
                "and raising the cap takes effect at once, with no catalogue rebuild");
    }

    /**
     * An errand kept on a list of its own neither fills the quest log nor is refused by it.
     *
     * <p>Both halves matter and they are the same switch. A player working several such errands
     * would otherwise find that many of their quest slots gone with nothing on the log screen to
     * account for them, and would then be refused the next errand for a log they are not filling.
     */
    @Test
    void anErrandOffTheLogNeitherFillsItNorIsRefusedByIt() {
        InMemoryQuestProgressStore store = new InMemoryQuestProgressStore();
        QuestEngine engine = QuestEngine.builder()
                .store(store)
                .maxActive(() -> 1)
                .nativeEvents(false)
                .build();
        Quest errand = Quest.builder("errand")
                .objective(ObjectiveDef.builder("step", "PICKUP_ITEM").target("Ore").build())
                .occupiesLog(false)
                .build();
        Quest secondErrand = Quest.builder("errand_two")
                .objective(ObjectiveDef.builder("step", "PICKUP_ITEM").target("Ore").build())
                .occupiesLog(false)
                .build();
        Quest ordinary = Quest.builder("ordinary")
                .objective(ObjectiveDef.builder("step", "PICKUP_ITEM").target("Ore").build())
                .build();
        engine.setQuests(List.of(errand, secondErrand, ordinary));

        engine.accept(PLAYER, errand);
        assertEquals(0, engine.logSlotsUsed(PLAYER),
                "carrying it spends no slot, which is what keeps the cap and a log screen's own"
                        + " count from telling the player two different numbers");
        assertTrue(engine.canAccept(PLAYER, ordinary).allowed(),
                "so an ordinary quest still fits under a cap of one");

        engine.accept(PLAYER, ordinary);
        assertEquals(1, engine.logSlotsUsed(PLAYER));
        assertTrue(engine.canAccept(PLAYER, secondErrand).allowed(),
                "and a full log never refuses one, since it was never going in the log");
    }

    /**
     * Availability is a PREDICATE the consumer supplies, so a feature toggled off between content
     * reloads takes the quest out of circulation without the catalogue being rebuilt.
     */
    @Test
    void availabilityIsReadLiveWhereverTheEngineAsks() {
        AtomicBoolean featureOn = new AtomicBoolean(true);
        InMemoryQuestProgressStore store = new InMemoryQuestProgressStore();
        QuestEngine engine = QuestEngine.builder().store(store).nativeEvents(false).build();
        Quest quest = Quest.builder("switchable")
                .available(featureOn::get)
                .objective(ObjectiveDef.builder("step", "PICKUP_ITEM").target("Ore").build())
                .build();
        engine.setQuests(List.of(quest));

        assertTrue(engine.canAccept(PLAYER, quest).allowed());

        featureOn.set(false);
        assertTrue(engine.canAccept(PLAYER, quest).reasons().contains(QuestGates.REASON_UNAVAILABLE),
                "the same cached quest object refuses once the feature behind it is off");
    }

    /** The achievement side reads the same predicate, at every place the engine asks. */
    @Test
    void anAchievementsAvailabilityIsReadLiveToo() {
        AtomicBoolean featureOn = new AtomicBoolean(true);
        AchievementEngine engine = AchievementEngine.builder()
                .store(new InMemoryAchievementProgressStore())
                .nativeEvents(false)
                .build();
        Achievement achievement = Achievement.builder("switchable")
                .available(featureOn::get)
                .criterion(ObjectiveDef.builder("0", "PICKUP_ITEM").target("Ore").amount(1).build())
                .build();
        engine.setAchievements(List.of(achievement));

        assertTrue(engine.isVisible(PLAYER, achievement));
        featureOn.set(false);
        assertFalse(engine.isVisible(PLAYER, achievement));
    }
}
