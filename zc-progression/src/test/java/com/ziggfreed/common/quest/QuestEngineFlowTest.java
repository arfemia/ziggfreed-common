package com.ziggfreed.common.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.progress.DispatchOptions;
import com.ziggfreed.common.progress.MatchMode;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.ObjectiveKindRegistry;
import com.ziggfreed.common.progress.ZoneRef;
import com.ziggfreed.common.subject.Subject;

/**
 * The engine's flow over an in-memory store: accept, dispatch, order gating, completion, claim, the
 * three dispatch shapes, tracking, and maintenance. Outbound events are switched off for most of it
 * (there is no event bus in a unit run) with one test proving the guard holds when they are on.
 */
class QuestEngineFlowTest {

    private static final long HOUR = 3_600_000L;

    private InMemoryQuestProgressStore store;
    private AtomicLong clock;
    private Subject player;
    private List<String> granted;
    private RewardKindRegistry rewardKinds;

    @BeforeEach
    void setUp() {
        store = new InMemoryQuestProgressStore();
        clock = new AtomicLong(1_000_000L);
        player = Subject.of(UUID.randomUUID(), "tester");
        granted = new ArrayList<>();
        rewardKinds = new RewardKindRegistry();
        rewardKinds.register("NOTE", (spec, subject) -> granted.add(spec.paramOr("text", "")));
    }

    @Nonnull
    private QuestEngine.Builder engine() {
        return QuestEngine.builder()
                .store(store)
                .rewardKinds(rewardKinds)
                .clock(clock::get)
                .nativeEvents(false)
                .warn(message -> { });
    }

    @Nonnull
    private static Quest.Builder quest(@Nonnull String id) {
        return Quest.builder(id);
    }

    @Nonnull
    private static ObjectiveDef objective(@Nonnull String id, @Nonnull String kind,
                                               @Nonnull String target, long amount) {
        return ObjectiveDef.builder(id, kind)
                .target(target).matchMode(MatchMode.EXACT).amount(amount).build();
    }

    // ==================== Accept -> progress -> complete -> claim ====================

    @Nested
    class HappyPath {

        @Test
        void aParkedQuestCompletesOnObjectivesAndPaysOnlyWhenClaimed() {
            Quest q = quest("q_gather")
                    .objective(objective("logs", "BREAK_BLOCK", "Oak_Log", 3))
                    .reward(RewardSpec.of("NOTE", "text", "paid"))
                    .build();
            QuestEngine engine = engine().build();
            engine.setQuests(List.of(q));

            assertTrue(engine.accept(player, q));
            assertEquals(QuestStatus.ACTIVE, engine.status(player, q));

            engine.dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 2);
            assertEquals(2, engine.progressOf(player, "q_gather", "logs").current());
            assertEquals(QuestStatus.ACTIVE, engine.status(player, q));

            engine.dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 1);
            assertEquals(QuestStatus.COMPLETED_UNCLAIMED, engine.status(player, q));
            assertTrue(granted.isEmpty(), "a parked quest must not pay out until it is collected");

            assertTrue(engine.claim(player, q));
            assertEquals(QuestStatus.COMPLETED, engine.status(player, q));
            assertEquals(List.of("paid"), granted);
            assertFalse(engine.claim(player, q), "collecting twice must not pay twice");
        }

        @Test
        void anAutoRewardQuestPaysTheMomentItsObjectivesAreMet() {
            Quest q = quest("q_auto")
                    .objective(objective("kills", "KILL_ENTITY", "Wolf", 1))
                    .autoReward(RewardSpec.of("NOTE", "text", "paid"))
                    .build();
            QuestEngine engine = engine().build();
            engine.setQuests(List.of(q));
            engine.accept(player, q);

            engine.dispatch(player, "KILL_ENTITY", "Wolf", null, 1);

            assertEquals(QuestStatus.COMPLETED, engine.status(player, q));
            assertEquals(List.of("paid"), granted);
        }

        @Test
        void progressPastTheRequirementNeitherOverCountsNorRePays() {
            Quest q = quest("q_over")
                    .objective(objective("kills", "KILL_ENTITY", "Wolf", 2))
                    .autoReward(RewardSpec.of("NOTE", "text", "paid"))
                    .build();
            QuestEngine engine = engine().build();
            engine.setQuests(List.of(q));
            engine.accept(player, q);

            engine.dispatch(player, "KILL_ENTITY", "Wolf", null, 5);
            engine.dispatch(player, "KILL_ENTITY", "Wolf", null, 5);

            assertEquals(2, engine.progressOf(player, "q_over", "kills").current());
            assertEquals(1, granted.size(), "one completion, one payout");
        }
    }

    // ==================== Ordering ====================

    @Nested
    class Ordering {

        @Test
        void aSequentialQuestUnlocksOneObjectiveAtATime() {
            Quest q = quest("q_seq")
                    .objective(objective("first", "BREAK_BLOCK", "Oak_Log", 1))
                    .objective(objective("second", "CRAFT_ITEM", "Plank", 1))
                    .sequential(true)
                    .build();
            QuestEngine engine = engine().build();
            engine.setQuests(List.of(q));
            engine.accept(player, q);

            engine.dispatch(player, "CRAFT_ITEM", "Plank", null, 1);
            assertEquals(0, engine.progressOf(player, "q_seq", "second").current(),
                    "a locked objective must not advance");

            engine.dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 1);
            engine.dispatch(player, "CRAFT_ITEM", "Plank", null, 1);
            assertEquals(1, engine.progressOf(player, "q_seq", "second").current());
            assertEquals(QuestStatus.COMPLETED, engine.status(player, q));
        }

        @Test
        void objectivesSharingAnOrderRunInParallelAndOrderZeroIsAlwaysOpen() {
            Quest q = quest("q_order")
                    .objective(ObjectiveDef.builder("anytime", "PICKUP_ITEM")
                            .target("Coin").matchMode(MatchMode.EXACT).amount(1).order(0).build())
                    .objective(ObjectiveDef.builder("step1a", "BREAK_BLOCK")
                            .target("Oak_Log").matchMode(MatchMode.EXACT).amount(1).order(1).build())
                    .objective(ObjectiveDef.builder("step1b", "BREAK_BLOCK")
                            .target("Stone").matchMode(MatchMode.EXACT).amount(1).order(1).build())
                    .objective(ObjectiveDef.builder("step2", "CRAFT_ITEM")
                            .target("Plank").matchMode(MatchMode.EXACT).amount(1).order(2).build())
                    .build();
            QuestEngine engine = engine().build();
            engine.setQuests(List.of(q));
            engine.accept(player, q);

            assertTrue(engine.objectiveActive(player, q, "anytime"));
            assertTrue(engine.objectiveActive(player, q, "step1a"));
            assertTrue(engine.objectiveActive(player, q, "step1b"));
            assertFalse(engine.objectiveActive(player, q, "step2"));

            engine.dispatch(player, "CRAFT_ITEM", "Plank", null, 1);
            assertEquals(0, engine.progressOf(player, "q_order", "step2").current());

            engine.dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 1);
            assertFalse(engine.objectiveActive(player, q, "step2"), "the whole group has to finish");
            engine.dispatch(player, "BREAK_BLOCK", "Stone", null, 1);
            assertTrue(engine.objectiveActive(player, q, "step2"));
        }

        @Test
        void theCurrentStepAdvancesAndKeepsUnorderedObjectivesInView() {
            Quest q = quest("q_step")
                    .objective(ObjectiveDef.builder("anytime", "PICKUP_ITEM")
                            .target("Coin").amount(1).order(0).build())
                    .objective(ObjectiveDef.builder("step1", "BREAK_BLOCK")
                            .target("Oak_Log").matchMode(MatchMode.EXACT).amount(1).order(1).build())
                    .objective(ObjectiveDef.builder("step2", "CRAFT_ITEM")
                            .target("Plank").matchMode(MatchMode.EXACT).amount(1).order(2).build())
                    .build();
            QuestEngine engine = engine().build();
            engine.setQuests(List.of(q));
            engine.accept(player, q);

            assertEquals(List.of("anytime", "step1"), ids(engine.activeStepObjectives(player, q)));
            engine.dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 1);
            assertEquals(List.of("anytime", "step2"), ids(engine.activeStepObjectives(player, q)));
            engine.dispatch(player, "CRAFT_ITEM", "Plank", null, 1);
            assertEquals(List.of("anytime", "step2"), ids(engine.activeStepObjectives(player, q)),
                    "with every ordered step done the final one stays on screen");
        }

        @Test
        void aQuestWithNoOrdersAndNoSequentialFlagOpensEverything() {
            Quest q = quest("q_open")
                    .objective(objective("a", "BREAK_BLOCK", "Oak_Log", 1))
                    .objective(objective("b", "CRAFT_ITEM", "Plank", 1))
                    .build();
            QuestEngine engine = engine().build();
            engine.setQuests(List.of(q));
            engine.accept(player, q);

            assertTrue(engine.objectiveActive(player, q, "a"));
            assertTrue(engine.objectiveActive(player, q, "b"));
            assertEquals(2, engine.activeStepObjectives(player, q).size());
        }

        private static List<String> ids(List<ObjectiveDef> objectives) {
            return objectives.stream().map(ObjectiveDef::id).toList();
        }
    }

    // ==================== Dispatch shapes ====================

    @Nested
    class DispatchShapes {

        private Quest named;
        private Quest matchAll;
        private QuestEngine engine;
        private List<String> tapped;

        @BeforeEach
        void content() {
            named = quest("q_named")
                    .objective(objective("named", "TALK_TO_NPC", "Guide", 1)).build();
            matchAll = quest("q_any")
                    .objective(ObjectiveDef.builder("any", "TALK_TO_NPC")
                            .target("").matchMode(MatchMode.PREFIX).amount(5).build()).build();
            tapped = new ArrayList<>();
            engine = engine().dispatchTap((subject, kind, target, qualifier, amount, zone) ->
                    tapped.add(kind + ":" + target)).build();
            engine.setQuests(List.of(named, matchAll));
            engine.accept(player, named);
            engine.accept(player, matchAll);
        }

        @Test
        void aFullDispatchAdvancesBothShapesAndIsSeenByTheTap() {
            engine.dispatch(player, "TALK_TO_NPC", "Guide", null, 1, null, DispatchOptions.FULL);

            assertEquals(1, engine.progressOf(player, "q_named", "named").current());
            assertEquals(1, engine.progressOf(player, "q_any", "any").current());
            assertEquals(List.of("TALK_TO_NPC:Guide"), tapped);
        }

        @Test
        void aTargetedDispatchSkipsMatchAllObjectivesSoAnAliasCannotDoubleCount() {
            engine.dispatch(player, "TALK_TO_NPC", "Guide", null, 1, null, DispatchOptions.FULL);
            engine.dispatch(player, "TALK_TO_NPC", "Guide", null, 1, null, DispatchOptions.TARGETED_ONLY);

            assertEquals(1, engine.progressOf(player, "q_named", "named").current(),
                    "the named objective completed on the first fire and cannot go further");
            assertEquals(1, engine.progressOf(player, "q_any", "any").current(),
                    "the match-all objective counted once, on the authoritative fire only");
            assertEquals(1, tapped.size(), "a follow-up fire is never tapped");
        }

        @Test
        void aTargetedDispatchStillAdvancesAnObjectiveThatNamesItsTarget() {
            engine.dispatch(player, "TALK_TO_NPC", "Guide", null, 1, null, DispatchOptions.TARGETED_ONLY);
            assertEquals(1, engine.progressOf(player, "q_named", "named").current());
            assertEquals(0, engine.progressOf(player, "q_any", "any").current());
        }

        @Test
        void anObjectivesOnlyDispatchAdvancesEverythingButIsNotTapped() {
            engine.dispatch(player, "TALK_TO_NPC", "Guide", null, 1, null, DispatchOptions.OBJECTIVES_ONLY);

            assertEquals(1, engine.progressOf(player, "q_named", "named").current());
            assertEquals(1, engine.progressOf(player, "q_any", "any").current());
            assertTrue(tapped.isEmpty());
        }

        @Test
        void theTapSeesAnEventNoObjectiveCaredAbout() {
            engine.dispatch(player, "TALK_TO_NPC", "Nobody", null, 1, null, DispatchOptions.FULL);
            assertEquals(List.of("TALK_TO_NPC:Nobody"), tapped);
        }
    }

    // ==================== Matching through dispatch ====================

    @Nested
    class MatchingThroughDispatch {

        @Test
        void aZoneScopedObjectiveOnlyProgressesInsideItsZone() {
            Quest q = quest("q_zone")
                    .objective(ObjectiveDef.builder("here", "BREAK_BLOCK")
                            .target("Oak_Log").matchMode(MatchMode.EXACT).amount(2)
                            .zone("Emerald_Grove").build())
                    .build();
            QuestEngine engine = engine().build();
            engine.setQuests(List.of(q));
            engine.accept(player, q);

            engine.dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 1, null, DispatchOptions.FULL);
            engine.dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 1,
                    new ZoneRef("Desert", "South"), DispatchOptions.FULL);
            assertEquals(0, engine.progressOf(player, "q_zone", "here").current());

            engine.dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 1,
                    new ZoneRef("emerald_grove", "North"), DispatchOptions.FULL);
            assertEquals(1, engine.progressOf(player, "q_zone", "here").current());
        }

        @Test
        void targetsCompareCaseInsensitively() {
            Quest q = quest("q_case").objective(objective("kills", "KILL_ENTITY", "Wolf", 1)).build();

            QuestEngine engine = engine().build();
            engine.setQuests(List.of(q));
            engine.accept(player, q);
            engine.dispatch(player, "KILL_ENTITY", "wolf", null, 1);
            assertEquals(1, engine.progressOf(player, "q_case", "kills").current(),
                    "one forgiving rule: a differently-cased id still counts the thing it names");
        }

        @Test
        void aValueBasedKindKeepsTheHighWaterMarkRatherThanSumming() {
            ObjectiveKindRegistry kinds = new ObjectiveKindRegistry();
            kinds.register("RUN_STREAK", "test", true, true);

            Quest q = quest("q_streak").objective(objective("streak", "RUN_STREAK", "any", 5)).build();
            QuestEngine engine = engine().objectiveKinds(kinds).build();
            engine.setQuests(List.of(q));
            engine.accept(player, q);

            engine.dispatch(player, "RUN_STREAK", "any", null, 3);
            engine.dispatch(player, "RUN_STREAK", "any", null, 2);
            assertEquals(3, engine.progressOf(player, "q_streak", "streak").current(),
                    "a lower current value must not be added to the recorded one");

            engine.dispatch(player, "RUN_STREAK", "any", null, 5);
            assertEquals(QuestStatus.COMPLETED, engine.status(player, q));
        }

        @Test
        void anObjectiveAuthoredAgainstAnUnknownKindNeverProgresses() {
            Quest q = quest("q_unknown").objective(objective("x", "NOT_A_KIND", "thing", 1)).build();
            QuestEngine engine = engine().build();
            engine.setQuests(List.of(q));
            engine.accept(player, q);

            engine.dispatch(player, "NOT_A_KIND", "thing", null, 1);
            assertEquals(0, engine.progressOf(player, "q_unknown", "x").current());
            assertEquals(QuestStatus.ACTIVE, engine.status(player, q));
        }
    }

    // ==================== Accept gating, visibility, auto-accept ====================

    @Nested
    class Gating {

        @Test
        void everyMechanicalRefusalIsReported() {
            Quest off = quest("q_off").available(false).build();
            Quest ok = quest("q_ok").objective(objective("a", "BREAK_BLOCK", "Oak_Log", 1)).build();
            QuestEngine engine = engine().maxActive(1).build();
            engine.setQuests(List.of(off, ok));

            assertEquals(List.of(QuestGates.REASON_UNAVAILABLE),
                    engine.canAccept(player, off).reasons());

            assertTrue(engine.canAccept(player, ok).allowed());
            engine.accept(player, ok);
            QuestEngine.AcceptCheck taken = engine.canAccept(player, ok);
            assertFalse(taken.allowed());
            assertTrue(taken.reasons().contains(QuestGates.REASON_ALREADY_STARTED));
            assertEquals(QuestGates.REASON_ALREADY_STARTED, taken.firstReason());
        }

        @Test
        void theActiveQuestCapIsEnforcedWhenSet() {
            Quest a = quest("q_a").objective(objective("x", "BREAK_BLOCK", "Oak_Log", 1)).build();
            Quest b = quest("q_b").objective(objective("x", "BREAK_BLOCK", "Stone", 1)).build();
            QuestEngine engine = engine().maxActive(1).build();
            engine.setQuests(List.of(a, b));

            engine.accept(player, a);
            assertTrue(engine.canAccept(player, b).reasons().contains(QuestGates.REASON_LOG_FULL));
        }

        @Test
        void aFinishedRepeatableRefusesUntilItsCooldownElapses() {
            Quest daily = quest("q_daily")
                    .objective(objective("x", "BREAK_BLOCK", "Oak_Log", 1))
                    .repeat(Quest.Repeat.every(24 * HOUR))
                    .build();
            QuestEngine engine = engine().build();
            engine.setQuests(List.of(daily));
            engine.accept(player, daily);
            engine.dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 1);

            assertEquals(QuestStatus.ON_COOLDOWN, engine.status(player, daily));
            assertTrue(engine.canAccept(player, daily).reasons().contains(QuestGates.REASON_ON_COOLDOWN));
            assertFalse(engine.accept(player, daily));

            clock.addAndGet(24 * HOUR);
            assertEquals(QuestStatus.NOT_STARTED, engine.status(player, daily));
            assertTrue(engine.accept(player, daily));
        }

        @Test
        void aConsumerGateCanRefuseAndAddItsOwnReason() {
            Quest q = quest("q_gated").visibility(new Quest.Visibility(false, true)).build();
            QuestEngine engine = engine().gates(new QuestGates() {
                @Override
                public boolean prerequisitesMet(@Nonnull Subject subject, @Nonnull Quest quest) {
                    return false;
                }

                @Override
                public boolean accepts(@Nonnull Subject subject, @Nonnull Quest quest,
                                       @Nonnull List<String> reasons) {
                    reasons.add("needs_a_friend");
                    return false;
                }
            }).build();
            engine.setQuests(List.of(q));

            QuestEngine.AcceptCheck check = engine.canAccept(player, q);
            assertFalse(check.allowed());
            assertTrue(check.reasons().contains(QuestGates.REASON_PREREQUISITES));
            assertTrue(check.reasons().contains("needs_a_friend"));
            assertFalse(engine.isVisible(player, q));
        }

        @Test
        void aStartedQuestStaysVisibleEvenWhenHidden() {
            Quest hidden = quest("q_hidden")
                    .objective(objective("x", "BREAK_BLOCK", "Oak_Log", 1))
                    .visibility(new Quest.Visibility(true, false))
                    .build();
            QuestEngine engine = engine().build();
            engine.setQuests(List.of(hidden));

            assertFalse(engine.isVisible(player, hidden));
            engine.accept(player, hidden);
            assertTrue(engine.isVisible(player, hidden));
        }

        /**
         * The two visibility reads answer DIFFERENT questions, and a hidden quest is exactly where
         * they part. Out of sight belongs to an open, browsable listing; at the one character
         * authored to hand this quest out there is no browsing going on, and hiding it there leaves
         * them standing silently beside the thing they exist to hand out - a whole authored chain
         * unreachable with nothing anywhere reporting it.
         */
        @Test
        void aHiddenQuestIsOffTheOpenListingAndStillOfferedAtItsGiver() {
            Quest hidden = quest("q_hidden_offer")
                    .objective(objective("x", "BREAK_BLOCK", "Oak_Log", 1))
                    .visibility(new Quest.Visibility(true, true))
                    .build();
            QuestEngine engine = engine().build();
            engine.setQuests(List.of(hidden));

            assertFalse(engine.isVisible(player, hidden),
                    "it is deliberately absent from a listing of everything in the world");
            assertTrue(engine.isOfferable(player, hidden),
                    "but the character whose quest it is has it to hand out");
        }

        /** The giver read still respects what a quest asks for first, and whether it is switched on. */
        @Test
        void theGiverReadStillHonoursPrerequisitesAndAvailability() {
            Quest gated = quest("q_gated_offer")
                    .visibility(new Quest.Visibility(true, true))
                    .build();
            QuestEngine engine = engine().gates(new QuestGates() {
                @Override
                public boolean prerequisitesMet(@Nonnull Subject subject, @Nonnull Quest quest) {
                    return false;
                }
            }).build();
            engine.setQuests(List.of(gated));

            assertFalse(engine.isOfferable(player, gated),
                    "a quest the player is not up to yet is not being handed out yet either");

            Quest off = quest("q_switched_off")
                    .visibility(new Quest.Visibility(true, true))
                    .available(() -> false)
                    .build();
            QuestEngine plain = engine().build();
            plain.setQuests(List.of(off));

            assertFalse(plain.isOfferable(player, off),
                    "and content switched off on this server is not offered anywhere");
        }

        @Test
        void anAutoAcceptQuestStartsItselfOnTheFirstQualifyingAction() {
            Quest q = quest("q_tutorial")
                    .objective(objective("x", "BREAK_BLOCK", "Oak_Log", 1))
                    .autoAccept(true).autoTrack(true)
                    .build();
            QuestEngine engine = engine().build();
            engine.setQuests(List.of(q));

            engine.dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 1);

            assertEquals(QuestStatus.COMPLETED, engine.status(player, q));
        }

        @Test
        void autoAcceptOnReadyStartsAndSettlesEligibleQuests() {
            Quest q = quest("q_ready")
                    .objective(objective("x", "BREAK_BLOCK", "Oak_Log", 1))
                    .autoAccept(true)
                    .build();
            QuestEngine engine = engine().gates(new QuestGates() {
                @Override
                public long preSatisfiedAmount(@Nonnull Subject subject, @Nonnull Quest quest,
                                               @Nonnull ObjectiveDef objective) {
                    return 1L;
                }
            }).build();
            engine.setQuests(List.of(q));

            assertEquals(1, engine.autoAcceptAvailable(player));
            assertEquals(QuestStatus.COMPLETED, engine.status(player, q),
                    "an objective already satisfied on accept settles immediately");
        }
    }

    // ==================== Tracking and maintenance ====================

    @Nested
    class TrackingAndMaintenance {

        @Test
        void pinsAreCappedOrderedAndReclaimedWhenTheirQuestStops() {
            Quest a = quest("q_a").objective(objective("x", "BREAK_BLOCK", "Oak_Log", 1)).build();
            Quest b = quest("q_b").objective(objective("x", "BREAK_BLOCK", "Stone", 1)).build();
            Quest c = quest("q_c").objective(objective("x", "BREAK_BLOCK", "Sand", 1)).build();
            QuestEngine engine = engine().maxTracked(2).build();
            engine.setQuests(List.of(a, b, c));
            engine.accept(player, a);
            engine.accept(player, b);
            engine.accept(player, c);

            assertTrue(engine.track(player, "q_a"));
            clock.addAndGet(10);
            assertTrue(engine.track(player, "q_b"));
            assertFalse(engine.track(player, "q_c"), "the cap holds");
            assertEquals(List.of("q_a", "q_b"), engine.tracked(player), "oldest pin first");

            engine.dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 1);
            assertEquals(1, engine.pruneStaleTracked(player), "a finished quest's pin is reclaimed");
            clock.addAndGet(10);
            assertTrue(engine.track(player, "q_c"), "and the freed slot is usable");
            assertEquals(List.of("q_b", "q_c"), engine.trackedActive(player).stream()
                    .map(Quest::id).toList());
        }

        @Test
        void anUnknownQuestCannotBePinned() {
            QuestEngine engine = engine().build();
            engine.setQuests(List.of());
            assertFalse(engine.track(player, "q_nothing"));
        }

        @Test
        void maintenanceResetsAFinishedRepeatableOnceItsCooldownElapses() {
            Quest daily = quest("q_daily")
                    .objective(objective("x", "BREAK_BLOCK", "Oak_Log", 1))
                    .repeat(Quest.Repeat.every(HOUR))
                    .build();
            QuestEngine engine = engine().build();
            engine.setQuests(List.of(daily));
            engine.accept(player, daily);
            engine.dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 1);

            assertEquals(0, engine.selfHeal(player), "nothing to do while the cooldown runs");
            clock.addAndGet(HOUR);
            assertEquals(1, engine.selfHeal(player));
            assertEquals(QuestStatus.NOT_STARTED, store.status(player, "q_daily"),
                    "what is stored now agrees with what every surface shows");
            assertTrue(engine.progressOf(player, "q_daily").isEmpty());
        }

        @Test
        void maintenanceLeavesAParkedRewardAlone() {
            Quest parked = quest("q_parked")
                    .objective(objective("x", "BREAK_BLOCK", "Oak_Log", 1))
                    .repeat(Quest.Repeat.every(HOUR))
                    .reward(RewardSpec.of("NOTE", "text", "parked"))
                    .build();
            QuestEngine engine = engine().build();
            engine.setQuests(List.of(parked));
            engine.accept(player, parked);
            engine.dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 1);

            clock.addAndGet(10 * HOUR);
            assertEquals(0, engine.selfHeal(player));
            assertEquals(QuestStatus.COMPLETED_UNCLAIMED, store.status(player, "q_parked"),
                    "a reward may still be owed");
        }

        @Test
        void maintenanceReArmsAnExternallyGovernedQuestAtOnce() {
            Quest governed = quest("q_governed")
                    .objective(objective("x", "BREAK_BLOCK", "Stone", 1))
                    .repeat(Quest.Repeat.EXTERNALLY_GOVERNED)
                    .build();
            QuestEngine engine = engine().build();
            engine.setQuests(List.of(governed));
            engine.accept(player, governed);
            engine.dispatch(player, "BREAK_BLOCK", "Stone", null, 1);

            assertEquals(1, engine.selfHeal(player),
                    "nothing on the quest holds it back, so whatever offers it decides when it comes"
                            + " round - the engine simply re-arms it");
            assertEquals(QuestStatus.NOT_STARTED, store.status(player, "q_governed"));
            assertEquals(1, store.completions(player, "q_governed").totalCount(),
                    "a re-arm keeps the completion record, or a lifetime cap could never be reached");
        }

        @Test
        void aCompleteAnchoredQuestKeepsTheClockItStartedWhenItParked() {
            Quest rotating = quest("q_rotating")
                    .objective(objective("x", "BREAK_BLOCK", "Stone", 1))
                    .repeat(new Quest.Repeat(4 * HOUR, Quest.Repeat.CooldownFrom.COMPLETE, null, 0))
                    .reward(RewardSpec.of("NOTE", "text", "parked"))
                    .build();
            QuestEngine engine = engine().build();
            engine.setQuests(List.of(rotating));
            engine.accept(player, rotating);
            engine.dispatch(player, "BREAK_BLOCK", "Stone", null, 1);
            long stampedAtPark = store.cooldownStamp(player, "q_rotating");

            clock.addAndGet(3 * HOUR);
            engine.claim(player, rotating);

            assertEquals(stampedAtPark, store.cooldownStamp(player, "q_rotating"),
                    "collecting later must not restart the clock");
            assertEquals(HOUR, engine.cooldownRemainingMs(player, rotating));
            assertEquals(1, store.completions(player, "q_rotating").totalCount(),
                    "parking then collecting is ONE completion, not two");
        }

        @Test
        void aClaimAnchoredQuestStartsItsClockWhenTheRewardIsTaken() {
            Quest parked = quest("q_parked")
                    .objective(objective("x", "BREAK_BLOCK", "Stone", 1))
                    .repeat(Quest.Repeat.every(4 * HOUR))
                    .reward(RewardSpec.of("NOTE", "text", "parked"))
                    .build();
            QuestEngine engine = engine().build();
            engine.setQuests(List.of(parked));
            engine.accept(player, parked);
            engine.dispatch(player, "BREAK_BLOCK", "Stone", null, 1);
            assertEquals(0L, store.cooldownStamp(player, "q_parked"),
                    "the clock has not started: the reward has not been taken");

            clock.addAndGet(HOUR);
            long collectedAt = clock.get();
            engine.claim(player, parked);

            assertEquals(collectedAt, store.cooldownStamp(player, "q_parked"));
            assertEquals(1, store.completions(player, "q_parked").totalCount());
        }

        @Test
        void aLifetimeCapAndASpentWindowRefuseWithTheirOwnReasons() {
            Quest capped = quest("q_capped")
                    .objective(objective("x", "BREAK_BLOCK", "Stone", 1))
                    .repeat(new Quest.Repeat(0L, Quest.Repeat.CooldownFrom.CLAIM, null, 1))
                    .build();
            Quest windowed = quest("q_windowed")
                    .objective(objective("x", "BREAK_BLOCK", "Oak_Log", 1))
                    .repeat(new Quest.Repeat(0L, Quest.Repeat.CooldownFrom.CLAIM,
                            Quest.Repeat.Reset.of(Quest.Repeat.Reset.Period.DAILY), 0))
                    .build();
            QuestEngine engine = engine().build();
            engine.setQuests(List.of(capped, windowed));
            engine.accept(player, capped);
            engine.accept(player, windowed);
            engine.dispatch(player, "BREAK_BLOCK", "Stone", null, 1);
            engine.dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 1);

            assertTrue(engine.canAccept(player, capped).reasons()
                    .contains(QuestGates.REASON_MAX_COMPLETIONS));
            assertTrue(engine.canAccept(player, windowed).reasons()
                    .contains(QuestGates.REASON_PERIOD_SPENT));
            assertEquals(0, engine.selfHeal(player),
                    "neither is offerable yet, so neither is re-armed");
        }

        @Test
        void aStoreThatCannotRememberCompletionsSaysSoOnce() {
            List<String> warnings = new ArrayList<>();
            Quest windowed = quest("q_windowed")
                    .objective(objective("x", "BREAK_BLOCK", "Oak_Log", 1))
                    .repeat(new Quest.Repeat(0L, Quest.Repeat.CooldownFrom.CLAIM,
                            Quest.Repeat.Reset.of(Quest.Repeat.Reset.Period.DAILY), 0))
                    .build();
            QuestEngine engine = QuestEngine.builder()
                    .store(new ForgetfulStore())
                    .clock(clock::get)
                    .nativeEvents(false)
                    .warn(warnings::add)
                    .build();
            engine.setQuests(List.of(windowed));

            assertEquals(1, warnings.size(), "one line per quest, at load, not one per accept");
            assertTrue(warnings.get(0).contains("q_windowed"));
            assertTrue(engine.accept(player, windowed),
                    "with nothing to count against, the window simply does not apply");
        }

        /** A store that deliberately cannot remember completions, like a round-scoped one. */
        private static final class ForgetfulStore implements QuestProgressStore {

            private final InMemoryQuestProgressStore backing = new InMemoryQuestProgressStore();

            @Override
            public boolean recordsCompletions() {
                return false;
            }

            @Override
            @Nonnull
            public QuestStatus status(@Nonnull Subject subject, @Nonnull String questId) {
                return backing.status(subject, questId);
            }

            @Override
            public void setStatus(@Nonnull Subject subject, @Nonnull String questId,
                                  @Nonnull QuestStatus status) {
                backing.setStatus(subject, questId, status);
            }

            @Override
            public String progressPayload(@Nonnull Subject subject, @Nonnull String questId) {
                return backing.progressPayload(subject, questId);
            }

            @Override
            public void putProgressPayload(@Nonnull Subject subject, @Nonnull String questId,
                                           @Nonnull String payload) {
                backing.putProgressPayload(subject, questId, payload);
            }

            @Override
            public long cooldownStamp(@Nonnull Subject subject, @Nonnull String questId) {
                return backing.cooldownStamp(subject, questId);
            }

            @Override
            public void setCooldownStamp(@Nonnull Subject subject, @Nonnull String questId,
                                         long epochMs) {
                backing.setCooldownStamp(subject, questId, epochMs);
            }

            @Override
            @Nonnull
            public Set<String> knownQuestIds(@Nonnull Subject subject) {
                return backing.knownQuestIds(subject);
            }

            @Override
            public void clearQuest(@Nonnull Subject subject, @Nonnull String questId) {
                backing.clearQuest(subject, questId);
            }

            @Override
            @Nonnull
            public Map<String, Long> trackedPins(@Nonnull Subject subject) {
                return backing.trackedPins(subject);
            }

            @Override
            public void setTrackedPin(@Nonnull Subject subject, @Nonnull String questId,
                                      long pinnedAtMs) {
                backing.setTrackedPin(subject, questId, pinnedAtMs);
            }

            @Override
            public boolean clearTrackedPin(@Nonnull Subject subject, @Nonnull String questId) {
                return backing.clearTrackedPin(subject, questId);
            }
        }

        @Test
        void maintenanceLeavesAQuestWhoseDefinitionIsGoneUntouched() {
            Quest daily = quest("q_daily")
                    .objective(objective("x", "BREAK_BLOCK", "Oak_Log", 1))
                    .repeat(Quest.Repeat.every(HOUR)).build();
            QuestEngine engine = engine().build();
            engine.setQuests(List.of(daily));
            engine.accept(player, daily);
            engine.dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 1);

            engine.setQuests(List.of());
            clock.addAndGet(10 * HOUR);
            assertEquals(0, engine.selfHeal(player));
            assertEquals(QuestStatus.COMPLETED, store.status(player, "q_daily"));
        }

        @Test
        void abandoningAnActiveQuestDiscardsItsProgressAndReOffersIt() {
            Quest q = quest("q_give_up").objective(objective("x", "BREAK_BLOCK", "Oak_Log", 3)).build();
            QuestEngine engine = engine().build();
            engine.setQuests(List.of(q));
            engine.accept(player, q);
            engine.track(player, "q_give_up");
            engine.dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 1);

            assertTrue(engine.abandon(player, "q_give_up"));
            assertEquals(QuestStatus.NOT_STARTED, engine.status(player, q));
            assertTrue(engine.progressOf(player, "q_give_up").isEmpty());
            assertTrue(engine.tracked(player).isEmpty());
            assertFalse(engine.abandon(player, "q_give_up"), "there is nothing left to give up");
        }

        @Test
        void forceCompletePaysOutWithoutObjectivesAndOnlyOnce() {
            Quest q = quest("q_skip")
                    .objective(objective("x", "BREAK_BLOCK", "Oak_Log", 99))
                    .reward(RewardSpec.of("NOTE", "text", "paid"))
                    .build();
            QuestEngine engine = engine().build();
            engine.setQuests(List.of(q));
            engine.accept(player, q);

            assertTrue(engine.forceComplete(player, q));
            assertEquals(QuestStatus.COMPLETED, engine.status(player, q));
            assertEquals(1, granted.size());
            assertFalse(engine.forceComplete(player, q), "a finished one-shot is not paid twice");
            assertEquals(1, granted.size());
        }
    }

    // ==================== Bookkeeping ====================

    @Test
    void theTallyCountsFinishedObjectivesAndNeverDividesByZero() {
        Quest q = quest("q_tally")
                .objective(objective("a", "BREAK_BLOCK", "Oak_Log", 1))
                .objective(objective("b", "BREAK_BLOCK", "Stone", 1))
                .build();
        QuestEngine engine = engine().build();
        engine.setQuests(List.of(q, quest("q_empty").build()));
        engine.accept(player, q);

        assertEquals(new QuestEngine.ObjectiveTally(0, 2), engine.tally(player, q));
        engine.dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 1);
        assertEquals(new QuestEngine.ObjectiveTally(1, 2), engine.tally(player, q));
        assertEquals(1, engine.tally(player, engine.quest("q_empty")).total());
    }

    @Test
    void theIndexNarrowsWorkToTheKindsContentActuallyListensFor() {
        Quest q = quest("q_index")
                .objective(objective("a", "BREAK_BLOCK", "Oak_Log", 1))
                .objective(objective("b", "BREAK_BLOCK", "Stone", 1))
                .objective(objective("c", "CRAFT_ITEM", "Plank", 1))
                .build();
        QuestEngine engine = engine().build();
        engine.setQuests(List.of(q));

        assertEquals(3, engine.index().size());
        assertEquals(2, engine.index().forKind("break_block").size(), "kind lookup is case-blind");
        assertTrue(engine.index().forKind("KILL_ENTITY").isEmpty());
        assertTrue(engine.index().forKind(null).isEmpty());
    }

    @Test
    void activeAndUnclaimedListsWhatThePlayerStillHasInHand() {
        Quest running = quest("q_running").objective(objective("x", "BREAK_BLOCK", "Oak_Log", 5)).build();
        Quest waiting = quest("q_waiting")
                .objective(objective("x", "BREAK_BLOCK", "Stone", 1))
                .reward(RewardSpec.of("NOTE", "text", "parked")).build();
        Quest done = quest("q_done").objective(objective("x", "BREAK_BLOCK", "Sand", 1)).build();
        QuestEngine engine = engine().build();
        engine.setQuests(List.of(running, waiting, done));
        engine.accept(player, running);
        engine.accept(player, waiting);
        engine.accept(player, done);
        engine.dispatch(player, "BREAK_BLOCK", "Stone", null, 1);
        engine.dispatch(player, "BREAK_BLOCK", "Sand", null, 1);

        List<String> ids = engine.activeAndUnclaimed(player).stream().map(Quest::id).sorted().toList();
        assertEquals(List.of("q_running", "q_waiting"), ids);
        assertEquals(1, engine.activeCount(player));
    }

    @Test
    void firingOutboundEventsWithNoEventBusPresentIsHarmless() {
        Quest q = quest("q_events")
                .objective(objective("x", "BREAK_BLOCK", "Oak_Log", 1))
                .tag("demo")
                .build();
        QuestEngine engine = QuestEngine.builder()
                .store(store).clock(clock::get).warn(message -> { })
                .nativeEvents(true)
                .build();
        engine.setQuests(List.of(q));

        engine.accept(player, q);
        engine.dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 1);

        assertEquals(QuestStatus.COMPLETED, engine.status(player, q),
                "the quest must run to completion even with nowhere to publish to");
    }

    @Test
    void theStoreDecidesWhichIdCharactersAreUnsafe() {
        QuestProgressStore plain = new InMemoryQuestProgressStore();
        assertFalse(plain.usesReservedDelimiter("gather_logs"));
        for (String bad : new String[] {null, "  ", "a:b", "a,b", "a=b", "a|b"}) {
            assertTrue(plain.usesReservedDelimiter(bad), "should be rejected: " + bad);
        }
    }

    @Test
    void aSubjectHandleComesBackOnlyAsWhatItReallyIs() {
        Map<String, String> handle = Map.of("k", "v");
        Subject subject = new Subject(UUID.randomUUID(), "held", handle);
        assertEquals(handle, subject.handleAs(Map.class));
        assertEquals(null, subject.handleAs(List.class));
        assertEquals(null, Subject.of(UUID.randomUUID(), "bare").handleAs(Map.class));
    }
}
