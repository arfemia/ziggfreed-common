package com.ziggfreed.common.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.loot.reward.RewardGrants;
import com.ziggfreed.common.loot.reward.RewardHandler;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.progress.MatchMode;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.ObjectiveKind;
import com.ziggfreed.common.subject.Subject;

/**
 * Hand-ins: the report-back shape with nothing to deliver, the item shape over a fake inventory
 * (including partial delivery), place-locked hand-ins, and the difference between "go here next" and
 * "you can actually hand this in".
 */
class QuestEngineTurnInTest {

    /** A stand-in inventory: a bag of counts the probe reads and the consumer draws down. */
    private static final class Bag implements QuestPossessionProbe, QuestInventoryConsumer {

        private final Map<String, Integer> counts = new HashMap<>();

        void put(@Nonnull String itemId, int count) {
            counts.put(itemId, count);
        }

        int count(@Nonnull String itemId) {
            return counts.getOrDefault(itemId, 0);
        }

        @Override
        public boolean holds(@Nonnull Subject subject, @Nonnull String itemId, int count) {
            return count(itemId) >= count;
        }

        @Override
        public int take(@Nonnull Subject subject, @Nonnull String itemId, int max) {
            int taken = Math.min(max, count(itemId));
            counts.put(itemId, count(itemId) - taken);
            return taken;
        }
    }

    private InMemoryQuestProgressStore store;
    private Subject player;
    private Bag bag;

    @BeforeEach
    void setUp() {
        store = new InMemoryQuestProgressStore();
        player = Subject.of(UUID.randomUUID(), "tester");
        bag = new Bag();
    }

    @Nonnull
    private QuestEngine engineWith(@Nonnull Quest... quests) {
        return engineWith(new RewardKindRegistry(), quests);
    }

    @Nonnull
    private QuestEngine engineWith(@Nonnull RewardKindRegistry rewardKinds, @Nonnull Quest... quests) {
        QuestEngine engine = QuestEngine.builder()
                .store(store)
                .rewardKinds(rewardKinds)
                .possessionProbe(bag)
                .inventoryConsumer(bag)
                .nativeEvents(false)
                .warn(message -> { })
                .build();
        engine.setQuests(List.of(quests));
        return engine;
    }

    /** What the stand-in rolled table below reports handed over: never its own spec. */
    private static final RewardSpec ROLLED =
            RewardSpec.of("Item", Map.of("Item", "Coin_Gold", "Count", "3"));

    /** A kind that only learns what it pays while paying it, standing in for a rolled table. */
    @Nonnull
    private static RewardKindRegistry rollingKind() {
        RewardKindRegistry kinds = new RewardKindRegistry();
        kinds.register("ROLL", new RewardHandler() {
            @Override
            public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject) {
            }

            @Override
            public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject,
                    @Nonnull String sourceId, @Nonnull Consumer<RewardSpec> receipt) {
                receipt.accept(ROLLED);
            }
        });
        return kinds;
    }

    @Nonnull
    private static ObjectiveDef handIn(@Nonnull String id, @Nonnull String itemId, long amount) {
        return ObjectiveDef.builder(id, "TURN_IN")
                .target(itemId).matchMode(MatchMode.EXACT).amount(amount).build();
    }

    @Test
    void anItemHandInTakesWhatIsOwedAndCompletesTheQuest() {
        Quest q = Quest.builder("q_deliver").objective(handIn("give", "Iron_Ore", 5)).build();
        QuestEngine engine = engineWith(q);
        engine.accept(player, q);
        bag.put("Iron_Ore", 10);

        assertEquals(5, engine.attemptTurnIn(player, q, "give"));
        assertEquals(5, bag.count("Iron_Ore"), "only what was owed is taken");
        assertEquals(QuestStatus.COMPLETED, engine.status(player, q));
    }

    @Test
    void aPartialHandInCreditsWhatWasActuallyTakenAndLeavesTheRestOwing() {
        Quest q = Quest.builder("q_deliver").objective(handIn("give", "Iron_Ore", 5)).build();
        QuestEngine engine = engineWith(q);
        engine.accept(player, q);
        bag.put("Iron_Ore", 2);

        assertEquals(2, engine.attemptTurnIn(player, q, "give"));
        assertEquals(0, bag.count("Iron_Ore"));
        assertEquals(3, engine.remainingFor(player, q,
                q.objective("give")), "the rest is still owed");
        assertEquals(QuestStatus.ACTIVE, engine.status(player, q));

        bag.put("Iron_Ore", 3);
        assertEquals(3, engine.attemptTurnIn(player, q, "give"));
        assertEquals(QuestStatus.COMPLETED, engine.status(player, q));
    }

    @Test
    void anEmptyBagHandsInNothingAndChangesNothing() {
        Quest q = Quest.builder("q_deliver").objective(handIn("give", "Iron_Ore", 5)).build();
        QuestEngine engine = engineWith(q);
        engine.accept(player, q);

        assertEquals(0, engine.attemptTurnIn(player, q, "give"));
        assertEquals(0, engine.progressOf(player, "q_deliver", "give").current());
        assertEquals(QuestStatus.ACTIVE, engine.status(player, q));
    }

    @Test
    void aReportBackHandInCompletesWithAnEmptyInventory() {
        Quest q = Quest.builder("q_return")
                .objective(ObjectiveDef.builder("report", "TURN_IN").target("").amount(1).build())
                .build();
        QuestEngine engine = engineWith(q);
        engine.accept(player, q);

        assertEquals(1, engine.attemptTurnIn(player, q, "report"));
        assertEquals(QuestStatus.COMPLETED, engine.status(player, q));
    }

    @Test
    void aHandInLockedBehindAnEarlierStepCannotBeUsedYet() {
        Quest q = Quest.builder("q_two_step")
                .objective(ObjectiveDef.builder("gather", "BREAK_BLOCK")
                        .target("Oak_Log").matchMode(MatchMode.EXACT).amount(1).order(1).build())
                .objective(ObjectiveDef.builder("give", "TURN_IN")
                        .target("Oak_Log").matchMode(MatchMode.EXACT).amount(1).order(2).build())
                .build();
        QuestEngine engine = engineWith(q);
        engine.accept(player, q);
        bag.put("Oak_Log", 5);

        assertEquals(0, engine.attemptTurnIn(player, q, "give"));
        assertNull(engine.firstActiveTurnIn(player, q, null));

        engine.dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 1);
        assertEquals("give", engine.firstActiveTurnIn(player, q, null).id());
        assertEquals(1, engine.attemptTurnIn(player, q, "give"));
    }

    @Test
    void aPlaceLockedHandInIsOnlyOfferedAtThePlaceItIsLockedTo() {
        Quest q = Quest.builder("q_locked")
                .objective(ObjectiveDef.builder("give", "TURN_IN")
                        .target("Iron_Ore").matchMode(MatchMode.EXACT).amount(1)
                        .turnInLockId("Quartermaster").build())
                .build();
        QuestEngine engine = engineWith(q);
        engine.accept(player, q);
        bag.put("Iron_Ore", 1);

        assertNull(engine.firstActiveTurnIn(player, q, null),
                "an anywhere-lookup skips every place-locked hand-in");
        assertNull(engine.firstActiveTurnIn(player, q, "Someone_Else"));
        assertEquals("give", engine.firstActiveTurnIn(player, q, "quartermaster").id(),
                "the place match is case-blind");
        assertTrue(engine.readyToTurnInAt(player, q, "Quartermaster"));
        assertFalse(engine.readyToTurnInAt(player, q, "Someone_Else"));
    }

    @Test
    void anUnlockedHandInIsOfferedAnywhere() {
        Quest q = Quest.builder("q_anywhere").objective(handIn("give", "Iron_Ore", 1)).build();
        QuestEngine engine = engineWith(q);
        engine.accept(player, q);

        assertEquals("give", engine.firstActiveTurnIn(player, q, null).id());
        assertEquals("give", engine.firstActiveTurnIn(player, q, "Anybody").id());
    }

    @Test
    void readinessIgnoresTheInventoryButDeliverabilityDoesNot() {
        Quest q = Quest.builder("q_deliver").objective(handIn("give", "Iron_Ore", 5)).build();
        QuestEngine engine = engineWith(q);
        engine.accept(player, q);

        assertTrue(engine.readyToTurnInAt(player, q, "Anybody"),
                "listing may rank it as the next step with nothing in hand");
        assertFalse(engine.canDeliverTurnInAt(player, q, "Anybody"),
                "but offering the hand-in must wait for something to hand over");

        bag.put("Iron_Ore", 4);
        assertTrue(engine.canDeliverTurnInAt(player, q, "Anybody"),
                "a partial load is offered: the hand-in credits whatever was brought");
        bag.put("Iron_Ore", 5);
        assertTrue(engine.canDeliverTurnInAt(player, q, "Anybody"));
    }

    @Test
    void onlyTheWholeRemainingAmountSettlesTheQuest() {
        Quest q = Quest.builder("q_settle").objective(handIn("give", "Iron_Ore", 5)).build();
        QuestEngine engine = engineWith(q);
        engine.accept(player, q);

        assertFalse(engine.settlesTurnInAt(player, q, "Anybody"), "empty-handed settles nothing");
        bag.put("Iron_Ore", 4);
        assertFalse(engine.settlesTurnInAt(player, q, "Anybody"),
                "a short load is deliverable but does not finish it");
        bag.put("Iron_Ore", 5);
        assertTrue(engine.settlesTurnInAt(player, q, "Anybody"));
    }

    @Test
    void otherOutstandingWorkKeepsAHandInFromSettling() {
        Quest q = Quest.builder("q_mixed")
                .objective(ObjectiveDef.builder("break", "BREAK_BLOCK").target("Rock").amount(10)
                        .order(1).build())
                .objective(ObjectiveDef.builder("give", "TURN_IN").target("Iron_Ore").amount(2)
                        .order(1).build())
                .build();
        QuestEngine engine = engineWith(q);
        engine.accept(player, q);
        bag.put("Iron_Ore", 2);

        assertTrue(engine.canDeliverTurnInAt(player, q, "Anybody"),
                "the ore may still be left here while the digging goes on");
        assertFalse(engine.settlesTurnInAt(player, q, "Anybody"),
                "but the quest is not ready while another step is outstanding");

        engine.dispatch(player, "BREAK_BLOCK", "Rock", null, 10);
        assertTrue(engine.settlesTurnInAt(player, q, "Anybody"));
    }

    @Test
    void onePassHandsInEveryStepACharacterIsOwed() {
        Quest q = Quest.builder("q_three")
                .objective(handIn("essence", "Life_Essence", 3))
                .objective(handIn("fibre", "Fibre", 2))
                .objective(handIn("leather", "Leather", 1))
                .build();
        QuestEngine engine = engineWith(q);
        engine.accept(player, q);
        bag.put("Life_Essence", 3);
        bag.put("Fibre", 2);
        bag.put("Leather", 1);

        assertTrue(engine.settlesTurnInAt(player, q, "Anybody"));
        assertEquals(6, engine.attemptAllTurnIns(player, q, "Anybody"),
                "one press discharges the whole errand");
        assertTrue(engine.allObjectivesComplete(player, q));
    }

    @Test
    void onePassCreditsWhatIsCarriedAndLeavesTheRest() {
        Quest q = Quest.builder("q_short")
                .objective(handIn("essence", "Life_Essence", 5))
                .objective(handIn("fibre", "Fibre", 2))
                .build();
        QuestEngine engine = engineWith(q);
        engine.accept(player, q);
        bag.put("Life_Essence", 2);

        assertEquals(2, engine.attemptAllTurnIns(player, q, "Anybody"),
                "a part-load is credited and the pass stops where the player runs out");
        assertFalse(engine.allObjectivesComplete(player, q));
        assertEquals(3, engine.remainingFor(player, q, q.objective("essence")));
    }

    // ==================== What a hand-in paid ====================

    /**
     * The press that finishes a quest where it pays out answers what the payout actually handed
     * over - what the roll produced, never the table's own spec - so a hand-in button's toast can
     * list it. The counting form still answers the count.
     */
    @Test
    void aHandInThatFinishesTheQuestAnswersWhatItPaid() {
        Quest q = Quest.builder("q_paid")
                .objective(handIn("give", "Iron_Ore", 2))
                .autoReward(RewardSpec.of("ROLL", "table", "demo"))
                .build();
        QuestEngine engine = engineWith(rollingKind(), q);
        engine.accept(player, q);
        bag.put("Iron_Ore", 2);

        QuestEngine.TurnInOutcome handed = engine.tryAllTurnIns(player, q, "Anybody");

        assertEquals(2, handed.credited());
        assertTrue(handed.creditedAny());
        assertEquals(QuestStatus.COMPLETED, engine.status(player, q));
        assertNotNull(handed.paid(), "the last step settled the quest, and the press says what that paid");
        assertEquals(List.of(ROLLED), handed.paid().receipt(),
                "what the roll produced, never the table's spec");
    }

    @Test
    void aSingleStepHandInAnswersWhatItPaidToo() {
        Quest q = Quest.builder("q_single")
                .objective(handIn("give", "Iron_Ore", 1))
                .autoReward(RewardSpec.of("ROLL", "table", "demo"))
                .build();
        QuestEngine engine = engineWith(rollingKind(), q);
        engine.accept(player, q);
        bag.put("Iron_Ore", 1);

        QuestEngine.TurnInOutcome handed = engine.tryTurnIn(player, q, "give", null);

        assertEquals(1, handed.credited());
        assertNotNull(handed.paid());
        assertEquals(List.of(ROLLED), handed.paid().receipt());
        assertEquals(QuestEngine.TurnInOutcome.NOTHING, engine.tryTurnIn(player, q, "give", null),
                "a finished quest has nothing left to hand in");
    }

    /**
     * A hand-in that PARKS the quest (a reward authored to be collected) has paid nothing, so it
     * answers no payout; the collect right behind it is what answers the receipt.
     */
    @Test
    void aHandInThatParksTheQuestAnswersNoPayoutAndTheCollectAnswersIt() {
        Quest q = Quest.builder("q_parked")
                .objective(handIn("give", "Iron_Ore", 2))
                .reward(RewardSpec.of("ROLL", "table", "demo"))
                .build();
        QuestEngine engine = engineWith(rollingKind(), q);
        engine.accept(player, q);
        bag.put("Iron_Ore", 2);

        QuestEngine.TurnInOutcome handed = engine.tryAllTurnIns(player, q, "Anybody");

        assertEquals(2, handed.credited());
        assertEquals(QuestStatus.COMPLETED_UNCLAIMED, engine.status(player, q));
        assertNull(handed.paid(), "nothing has been paid: the quest parked for collecting");

        RewardGrants.GrantOutcome paid = engine.tryClaim(player, q, "Anybody");

        assertNotNull(paid, "the collect answers what it paid");
        assertEquals(List.of(ROLLED), paid.receipt());
    }

    @Test
    void aHandInThatLeavesAStepOutstandingAnswersTheCreditAndNoPayout() {
        Quest q = Quest.builder("q_short_paid")
                .objective(handIn("give", "Iron_Ore", 5))
                .autoReward(RewardSpec.of("ROLL", "table", "demo"))
                .build();
        QuestEngine engine = engineWith(rollingKind(), q);
        engine.accept(player, q);
        bag.put("Iron_Ore", 2);

        QuestEngine.TurnInOutcome handed = engine.tryAllTurnIns(player, q, "Anybody");

        assertEquals(2, handed.credited());
        assertNull(handed.paid(), "the quest is still open, so nothing was paid");
        assertEquals(QuestStatus.ACTIVE, engine.status(player, q));

        bag.put("Iron_Ore", 3);
        assertEquals(3, engine.attemptAllTurnIns(player, q, "Anybody"),
                "the counting form answers the count of the press that finished it");
        assertEquals(QuestStatus.COMPLETED, engine.status(player, q));
    }

    @Test
    void aReportBackHandInIsDeliverableWithNothingAtAll() {
        Quest q = Quest.builder("q_return")
                .objective(ObjectiveDef.builder("report", "TURN_IN").target("").amount(1).build())
                .build();
        QuestEngine engine = engineWith(q);
        engine.accept(player, q);

        assertTrue(engine.canDeliverTurnInAt(player, q, "Anybody"));
    }

    @Test
    void aTalkStepNamingThisPlaceCountsAsReadyHere() {
        Quest q = Quest.builder("q_talk")
                .objective(ObjectiveDef.builder("speak", "TALK_TO_NPC")
                        .target("Guide").matchMode(MatchMode.EXACT).amount(1).build())
                .build();
        QuestEngine engine = engineWith(q);
        engine.accept(player, q);

        assertTrue(engine.readyToTurnInAt(player, q, "Guide"));
        assertFalse(engine.canDeliverTurnInAt(player, q, "Guide"),
                "the step resolves here but nothing changes hands, so no hand-in may be offered");
        assertFalse(engine.readyToTurnInAt(player, q, "Somebody"));
    }

    @Test
    void aPlaceIsNamedInFullOrNotAtAll() {
        Quest q = Quest.builder("q_talk")
                .objective(ObjectiveDef.builder("speak", "TALK_TO_NPC")
                        .target("Guide").matchMode(MatchMode.CONTAINS).amount(1).build())
                .build();
        QuestEngine engine = engineWith(q);
        engine.accept(player, q);

        assertTrue(engine.readyToTurnInAt(player, q, "GUIDE"), "a place is compared ignoring case");
        assertFalse(engine.readyToTurnInAt(player, q, "Guide_Of_The_Wilds"),
                "the authored match mode is the dialect for events, never for naming a place");
    }

    @Test
    void aStepNamingNobodyMarksNobody() {
        Quest q = Quest.builder("q_wildcard")
                .objective(ObjectiveDef.builder("speak", "TALK_TO_NPC").target("").amount(1).build())
                .build();
        QuestEngine engine = engineWith(q);
        engine.accept(player, q);

        assertFalse(engine.readyToTurnInAt(player, q, "Guide"),
                "a blank target matches every identifier there is, which is not a destination");
        assertFalse(engine.canDeliverTurnInAt(player, q, "Guide"));
    }

    @Test
    void aStepWhoseTargetIsAThingNeverMarksACharacter() {
        Quest q = Quest.builder("q_gather")
                .objective(ObjectiveDef.builder("gather", "BREAK_BLOCK")
                        .target("Guide").matchMode(MatchMode.EXACT).amount(1).build())
                .build();
        QuestEngine engine = engineWith(q);
        engine.accept(player, q);

        assertFalse(engine.readyToTurnInAt(player, q, "Guide"),
                "BREAK_BLOCK names a block, so its target can never be somewhere to stand");
    }

    @Test
    void aConsumerKindMayDeclareThatItsTargetIsAPlace() {
        QuestEngine engine = QuestEngine.builder()
                .store(store)
                .possessionProbe(bag)
                .inventoryConsumer(bag)
                .nativeEvents(false)
                .warn(message -> { })
                .build();
        engine.objectiveKinds().register(null, ObjectiveKind.placeTargeted("ESCORT_NPC"));
        Quest q = Quest.builder("q_escort")
                .objective(ObjectiveDef.builder("escort", "ESCORT_NPC")
                        .target("Guide").matchMode(MatchMode.EXACT).amount(1).build())
                .build();
        engine.setQuests(List.of(q));
        engine.accept(player, q);

        assertTrue(engine.readyToTurnInAt(player, q, "Guide"));
    }

    @Test
    void aQuestMidWayThroughSomethingElseIsNotReadyAnywhere() {
        Quest q = Quest.builder("q_busy")
                .objective(ObjectiveDef.builder("gather", "BREAK_BLOCK")
                        .target("Oak_Log").matchMode(MatchMode.EXACT).amount(5).build())
                .objective(handIn("give", "Oak_Log", 5))
                .sequential(true)
                .build();
        QuestEngine engine = engineWith(q);
        engine.accept(player, q);

        assertFalse(engine.readyToTurnInAt(player, q, "Anybody"));
    }

    @Test
    void aQuestThatIsNotBeingCarriedIsNeverReadyAndHandsInNothing() {
        Quest q = Quest.builder("q_deliver").objective(handIn("give", "Iron_Ore", 1)).build();
        QuestEngine engine = engineWith(q);
        bag.put("Iron_Ore", 5);

        assertFalse(engine.readyToTurnInAt(player, q, "Anybody"));
        assertEquals(0, engine.attemptTurnIn(player, q, "give"));
        assertEquals(5, bag.count("Iron_Ore"), "nothing was taken");
    }

    @Test
    void aFinishedHandInCannotBeUsedAgain() {
        Quest q = Quest.builder("q_deliver")
                .objective(handIn("give", "Iron_Ore", 1))
                .objective(ObjectiveDef.builder("other", "BREAK_BLOCK")
                        .target("Stone").matchMode(MatchMode.EXACT).amount(1).build())
                .build();
        QuestEngine engine = engineWith(q);
        engine.accept(player, q);
        bag.put("Iron_Ore", 5);

        assertEquals(1, engine.attemptTurnIn(player, q, "give"));
        assertEquals(0, engine.attemptTurnIn(player, q, "give"));
        assertEquals(4, bag.count("Iron_Ore"), "the second attempt took nothing");
    }

    @Test
    void anObjectiveThatIsNotAHandInIsRefused() {
        Quest q = Quest.builder("q_mixed")
                .objective(ObjectiveDef.builder("mine", "BREAK_BLOCK")
                        .target("Stone").matchMode(MatchMode.EXACT).amount(1).build())
                .build();
        QuestEngine engine = engineWith(q);
        engine.accept(player, q);

        assertEquals(0, engine.attemptTurnIn(player, q, "mine"));
        assertEquals(0, engine.attemptTurnIn(player, q, "no_such_objective"));
    }

    @Test
    void anEngineWithNoInventoryAccessRefusesItemHandInsButStillTakesReportBacks() {
        Quest items = Quest.builder("q_items").objective(handIn("give", "Iron_Ore", 1)).build();
        Quest report = Quest.builder("q_report")
                .objective(ObjectiveDef.builder("report", "TURN_IN").target("").amount(1).build())
                .build();
        QuestEngine engine = QuestEngine.builder()
                .store(store).nativeEvents(false).warn(message -> { }).build();
        engine.setQuests(List.of(items, report));
        engine.accept(player, items);
        engine.accept(player, report);

        assertEquals(0, engine.attemptTurnIn(player, items, "give"));
        assertFalse(engine.canDeliverTurnInAt(player, items, "Anybody"));
        assertEquals(1, engine.attemptTurnIn(player, report, "report"));
    }

    @Test
    void anInventoryClaimingMoreThanItWasAskedForIsClampedToWhatWasOwed() {
        Quest q = Quest.builder("q_deliver").objective(handIn("give", "Iron_Ore", 2)).build();
        QuestEngine engine = QuestEngine.builder()
                .store(store).nativeEvents(false).warn(message -> { })
                .possessionProbe(QuestPossessionProbe.ANY)
                .inventoryConsumer((subject, itemId, max) -> max + 100)
                .build();
        engine.setQuests(List.of(q));
        engine.accept(player, q);

        assertEquals(2, engine.attemptTurnIn(player, q, "give"));
        assertEquals(2, engine.progressOf(player, "q_deliver", "give").current());
    }

    @Test
    void anInventoryThatThrowsHandsInNothingRatherThanBreakingTheQuest() {
        Quest q = Quest.builder("q_deliver").objective(handIn("give", "Iron_Ore", 2)).build();
        QuestEngine engine = QuestEngine.builder()
                .store(store).nativeEvents(false).warn(message -> { })
                .possessionProbe(QuestPossessionProbe.ANY)
                .inventoryConsumer((subject, itemId, max) -> {
                    throw new IllegalStateException("inventory unavailable");
                })
                .build();
        engine.setQuests(List.of(q));
        engine.accept(player, q);

        assertEquals(0, engine.attemptTurnIn(player, q, "give"));
        assertEquals(QuestStatus.ACTIVE, engine.status(player, q));
    }

    @Test
    void aSubjectBlindProbeCanBeAdaptedForASinglePlayerConsumer() {
        Quest q = Quest.builder("q_deliver").objective(handIn("give", "Iron_Ore", 1)).build();
        QuestEngine engine = QuestEngine.builder()
                .store(store).nativeEvents(false).warn(message -> { })
                .possessionProbe((itemId, count) -> "Iron_Ore".equals(itemId) && count <= 3)
                .build();
        engine.setQuests(List.of(q));
        engine.accept(player, q);

        assertTrue(engine.canDeliverTurnInAt(player, q, "Anybody"));
    }
}
