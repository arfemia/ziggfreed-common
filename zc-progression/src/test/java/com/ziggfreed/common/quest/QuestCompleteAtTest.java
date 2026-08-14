package com.ziggfreed.common.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.progress.MatchMode;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.subject.Subject;

/**
 * WHERE a quest may be collected: the one predicate every surface asks, and the refusal the engine
 * makes in the completion path itself so a surface that never asks still cannot pay a quest out in
 * the wrong place.
 *
 * <p>The two forms answer different questions and are proved separately. A named character is known
 * from the content; the accepted-at form is known only from what the player did, so it also proves
 * that the place survives every later progress write and that a record made before the quest asked
 * for one refuses rather than opening.
 */
class QuestCompleteAtTest {

    private InMemoryQuestProgressStore store;
    private Subject player;
    private List<String> granted;
    private RewardKindRegistry rewardKinds;

    @BeforeEach
    void setUp() {
        store = new InMemoryQuestProgressStore();
        player = Subject.of(UUID.randomUUID(), "tester");
        granted = new ArrayList<>();
        rewardKinds = new RewardKindRegistry();
        rewardKinds.register("NOTE", (spec, subject) -> granted.add(spec.paramOr("text", "")));
    }

    @Nonnull
    private QuestEngine engineWith(@Nonnull Quest... quests) {
        QuestEngine engine = QuestEngine.builder()
                .store(store)
                .rewardKinds(rewardKinds)
                .nativeEvents(false)
                .warn(message -> { })
                .build();
        engine.setQuests(List.of(quests));
        return engine;
    }

    @Nonnull
    private static Quest.Builder parked(@Nonnull String id, @Nullable QuestTurnInSite site) {
        return Quest.builder(id)
                .objective(ObjectiveDef.builder("step", "BREAK_BLOCK")
                        .target("Stone").matchMode(MatchMode.EXACT).amount(1).build())
                .reward(RewardSpec.of("NOTE", "text", "paid"))
                .turnInAt(site)
                .autoClaim(false);
    }

    // ==================== The predicate ====================

    @Nested
    class TruthTable {

        @Test
        void aQuestNamingNoPlaceMayBeCollectedAnywhereIncludingNowhere() {
            Quest q = parked("q_open", null).build();
            QuestEngine engine = engineWith(q);
            engine.accept(player, q);

            assertTrue(engine.canCompleteAt(player, q, null), "the default must need no place at all");
            assertTrue(engine.canCompleteAt(player, q, "anybody"));
        }

        @Test
        void aCharacterBoundQuestAnswersOnlyToThatId() {
            Quest q = parked("q_guide", QuestTurnInSite.character("Guide")).build();
            QuestEngine engine = engineWith(q);
            engine.accept(player, q);

            assertTrue(engine.canCompleteAt(player, q, "guide"), "the id is matched ignoring case");
            assertTrue(engine.canCompleteAt(player, q, "GUIDE"));
            assertFalse(engine.canCompleteAt(player, q, "quartermaster"));
            assertFalse(engine.canCompleteAt(player, q, null),
                    "a claim from a log or a book is a claim from nowhere");
            assertFalse(engine.canCompleteAt(player, q, "  "));
        }

        /**
         * One character answering to several ids is resolved ABOVE the engine: the caller asks once
         * per id, which is what keeps every identity registry out of this module. This is that call
         * shape, proved on the seam a conversation actually holds.
         */
        @Test
        void anAliasIsAnsweredByAskingOncePerIdTheCharacterAnswersTo() {
            Quest q = parked("q_guide", QuestTurnInSite.character("temple_guide")).build();
            QuestEngine engine = engineWith(q);
            engine.accept(player, q);
            QuestStateReader reader = engine;

            assertTrue(answersAnywhereIn(reader, "q_guide", List.of("guide", "temple_guide")));
            assertFalse(answersAnywhereIn(reader, "q_guide", List.of("guide", "quartermaster")));
        }

        private static boolean answersAnywhereIn(@Nonnull QuestStateReader reader,
                @Nonnull String questId, @Nonnull List<String> answersTo) {
            for (String id : answersTo) {
                if (reader.canCompleteAt(Subject.of(UUID.randomUUID(), "x"), questId, id)) {
                    // The subject is irrelevant to a character-bound site, which is the point of
                    // asking per id rather than per player.
                    return true;
                }
            }
            return false;
        }

        @Test
        void aQuestThisEngineDoesNotCarryHasNoPlaceRuleToBreak() {
            QuestEngine engine = engineWith(parked("q_open", null).build());
            assertTrue(engine.canCompleteAt(player, "never_authored", null),
                    "a refusal gate with nothing to refuse must not hide every collection");
        }
    }

    // ==================== The accepted-at form ====================

    @Nested
    class AcceptedAtSite {

        @Test
        void acceptRecordsThePlaceAndCompletionAnswersOnlyToIt() {
            Quest q = parked("q_posting", QuestTurnInSite.ACCEPT_SITE).build();
            QuestEngine engine = engineWith(q);

            engine.accept(player, q, "North_Post");

            assertEquals("North_Post", engine.acceptSiteOf(player, "q_posting"));
            assertTrue(engine.canCompleteAt(player, q, "north_post"), "matched ignoring case");
            assertFalse(engine.canCompleteAt(player, q, "South_Post"));
            assertFalse(engine.canCompleteAt(player, q, null));
        }

        @Test
        void takingItAgainRecordsTheNewPlace() {
            Quest q = parked("q_posting", QuestTurnInSite.ACCEPT_SITE)
                    .repeat(Quest.Repeat.EXTERNALLY_GOVERNED).build();
            QuestEngine engine = engineWith(q);

            engine.accept(player, q, "North_Post");
            engine.abandon(player, "q_posting");
            engine.accept(player, q, "South_Post");

            assertFalse(engine.canCompleteAt(player, q, "North_Post"));
            assertTrue(engine.canCompleteAt(player, q, "South_Post"));
        }

        @Test
        void progressWritesAfterTheAcceptKeepThePlace() {
            Quest q = parked("q_posting", QuestTurnInSite.ACCEPT_SITE).build();
            QuestEngine engine = engineWith(q);
            engine.accept(player, q, "North_Post");

            engine.dispatch(player, "BREAK_BLOCK", "Stone", null, 1);

            assertEquals("North_Post", engine.acceptSiteOf(player, "q_posting"),
                    "a save that forgot the place would silently unbind the quest mid-run");
            assertEquals(QuestStatus.COMPLETED_UNCLAIMED, engine.status(player, q));
            assertTrue(engine.canCompleteAt(player, q, "North_Post"));
        }

        @Test
        void aRecordMadeBeforeTheQuestAskedForAPlaceRefusesEverywhere() {
            Quest q = parked("q_posting", QuestTurnInSite.ACCEPT_SITE).build();
            QuestEngine engine = engineWith(q);
            engine.accept(player, q);

            assertNull(engine.acceptSiteOf(player, "q_posting"));
            assertFalse(engine.canCompleteAt(player, q, "North_Post"),
                    "with nothing recorded there is nothing that can match");
            assertFalse(engine.canCompleteAt(player, q, null));
        }

        @Test
        void aPlaceTheProgressFormatCannotHoldIsNotRecorded() {
            Quest q = parked("q_posting", QuestTurnInSite.ACCEPT_SITE).build();
            QuestEngine engine = engineWith(q);

            engine.accept(player, q, "north|post");

            assertNull(engine.acceptSiteOf(player, "q_posting"),
                    "half an id would match nothing and read as a working record");
        }
    }

    // ==================== The refusal the engine makes itself ====================

    @Nested
    class CompletionPath {

        @Test
        void collectingFromNowhereIsRefusedAndTheQuestStaysWaiting() {
            Quest q = parked("q_guide", QuestTurnInSite.character("guide")).build();
            QuestEngine engine = engineWith(q);
            engine.accept(player, q);
            engine.dispatch(player, "BREAK_BLOCK", "Stone", null, 1);
            assertEquals(QuestStatus.COMPLETED_UNCLAIMED, engine.status(player, q));

            assertFalse(engine.claim(player, q), "a book has no place attached to it");
            assertFalse(engine.claim(player, q, "quartermaster"));
            assertTrue(granted.isEmpty(), "nothing may be paid out at the wrong place");
            assertEquals(QuestStatus.COMPLETED_UNCLAIMED, engine.status(player, q),
                    "a refused collection must leave the quest collectable later");

            assertTrue(engine.claim(player, q, "Guide"));
            assertEquals(List.of("paid"), granted);
            assertEquals(QuestStatus.COMPLETED, engine.status(player, q));
        }

        @Test
        void anAutoClaimQuestBoundToAPlaceParksInsteadOfPayingWhereverItFinishes() {
            Quest q = Quest.builder("q_auto")
                    .objective(ObjectiveDef.builder("step", "BREAK_BLOCK")
                            .target("Stone").matchMode(MatchMode.EXACT).amount(1).build())
                    .reward(RewardSpec.of("NOTE", "text", "paid"))
                    .turnInAt(QuestTurnInSite.character("guide"))
                    .build();
            QuestEngine engine = engineWith(q);
            engine.accept(player, q);

            engine.dispatch(player, "BREAK_BLOCK", "Stone", null, 1);

            assertEquals(QuestStatus.COMPLETED_UNCLAIMED, engine.status(player, q),
                    "finishing the steps out in the world cannot pay a quest owed to a character");
            assertTrue(granted.isEmpty());

            assertTrue(engine.claim(player, q, "guide"));
            assertEquals(List.of("paid"), granted);
        }

        @Test
        void aHandInAtTheQuestsOwnPlacePaysOutOnTheSpot() {
            Quest q = reportBackTo("guide");
            QuestEngine engine = engineWith(q);
            engine.accept(player, q);

            assertEquals(1, engine.attemptTurnIn(player, q, "step", "guide"));

            assertEquals(QuestStatus.COMPLETED, engine.status(player, q),
                    "the player is standing at the place, so there is nothing to come back for");
            assertEquals(List.of("paid"), granted);
        }

        @Test
        void theSameHandInWithNowhereNamedParksItInstead() {
            Quest q = reportBackTo("guide");
            QuestEngine engine = engineWith(q);
            engine.accept(player, q);

            assertEquals(1, engine.attemptTurnIn(player, q, "step"));

            assertEquals(QuestStatus.COMPLETED_UNCLAIMED, engine.status(player, q));
            assertTrue(granted.isEmpty());
        }

        @Nonnull
        private Quest reportBackTo(@Nonnull String npcId) {
            return Quest.builder("q_report")
                    .objective(ObjectiveDef.builder("step", "TURN_IN").target("").amount(1).build())
                    .reward(RewardSpec.of("NOTE", "text", "paid"))
                    .turnInAt(QuestTurnInSite.character(npcId))
                    .build();
        }

        @Test
        void anAdministratorsForcedCompletionIgnoresThePlaceLikeItIgnoresTheSteps() {
            Quest q = parked("q_guide", QuestTurnInSite.character("guide")).build();
            QuestEngine engine = engineWith(q);
            engine.accept(player, q);

            assertTrue(engine.forceComplete(player, q));
            assertEquals(List.of("paid"), granted);
        }

        @Test
        void aQuestNamingNoPlaceIsUnaffected() {
            Quest q = parked("q_open", null).build();
            QuestEngine engine = engineWith(q);
            engine.accept(player, q);
            engine.dispatch(player, "BREAK_BLOCK", "Stone", null, 1);

            assertTrue(engine.claim(player, q), "the default path must not have moved");
            assertEquals(List.of("paid"), granted);
        }
    }
}
