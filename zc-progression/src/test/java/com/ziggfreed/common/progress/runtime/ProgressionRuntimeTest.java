package com.ziggfreed.common.progress.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.AchievementEngine;
import com.ziggfreed.common.achievement.AchievementGates;
import com.ziggfreed.common.progress.MatchMode;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.quest.InMemoryQuestProgressStore;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.QuestGates;
import com.ziggfreed.common.quest.QuestProgressStore;
import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.subject.Subject;

/**
 * The registration surface: who wins a one-slot part, what a second consumer wanting the same one is
 * told, how contributions compose, and what the engines end up built over.
 *
 * <p>Every case here is about the failure the shared runtime exists to make impossible - two answers
 * to one question, picked between silently. A refusal that is loud and a composition that keeps
 * everybody's answer are the two halves of that, so both are pinned.
 */
class ProgressionRuntimeTest {

    private static final String LIBRARY = "ziggfreedcommon";
    private static final String CONSUMER = "yourmod";
    private static final String OTHER = "othermod";

    private Subject player;

    @BeforeEach
    void setUp() {
        ProgressionRuntime.resetForTests();
        player = Subject.of(UUID.randomUUID(), "tester");
    }

    @AfterEach
    void tearDown() {
        ProgressionRuntime.resetForTests();
    }

    // ==================== one-slot precedence ====================

    @Test
    void aConsumerOutranksALibraryDefaultWhicheverRegistersFirst() {
        QuestProgressStore libraryStore = new InMemoryQuestProgressStore();
        QuestProgressStore consumerStore = new InMemoryQuestProgressStore();

        ProgressionRuntime.defaults(LIBRARY).questStore(libraryStore);
        ProgressionRuntime.registrar(CONSUMER).questStore(consumerStore);
        assertSame(consumerStore, ProgressionRuntime.parts().questStore());
        assertFalse(ProgressionRuntime.usesDefaultStores());

        // ... and the other way round, because load order must not decide this.
        ProgressionRuntime.resetForTests();
        ProgressionRuntime.registrar(CONSUMER).questStore(consumerStore);
        ProgressionRuntime.defaults(LIBRARY).questStore(libraryStore);
        assertSame(consumerStore, ProgressionRuntime.parts().questStore(),
                "a library default must never clobber a consumer that got there first");
    }

    @Test
    void aSecondConsumerIsRefusedAndTheFirstStands() {
        QuestProgressStore first = new InMemoryQuestProgressStore();
        QuestProgressStore second = new InMemoryQuestProgressStore();

        ProgressionRuntime.registrar(CONSUMER).questStore(first);
        ProgressionRuntime.registrar(OTHER).questStore(second);

        assertSame(first, ProgressionRuntime.parts().questStore(),
                "two mods each wanting their own store is unresolvable, and silently picking one is"
                        + " the double-tracking failure one level up");
    }

    @Test
    void reRegisteringTheSameInstanceIsSilentlyNothing() {
        QuestProgressStore store = new InMemoryQuestProgressStore();
        ProgressionRuntime.registrar(CONSUMER).questStore(store);
        ProgressionRuntime.registrar(CONSUMER).questStore(store);
        assertSame(store, ProgressionRuntime.parts().questStore());
    }

    @Test
    void aSealedScalarKeepsWhoeverAgreedWithItself() {
        ProgressionRuntime.defaults(LIBRARY).maxTrackedQuests(5);
        ProgressionRuntime.registrar(CONSUMER).maxTrackedQuests(5);
        assertEquals(5, ProgressionRuntime.quests().maxTracked(),
                "the two agreeing costs nothing and says nothing");
    }

    @Test
    void anUnregisteredRuntimeStillAnswersWithWorkingEngines() {
        assertNotNull(ProgressionRuntime.quests());
        assertNotNull(ProgressionRuntime.achievements());
        assertTrue(ProgressionRuntime.isBuilt(),
                "reading an engine builds the runtime, so a caller never sees null");
        assertTrue(ProgressionRuntime.usesDefaultStores());
    }

    // ==================== contributions ====================

    @Test
    void everyGateAppliesAndEveryRefusalIsKept() {
        ProgressionRuntime.registrar(CONSUMER).questGates(refusing("one"));
        ProgressionRuntime.registrar(OTHER).questGates(refusing("two"));

        Quest quest = Quest.builder("q").build();
        List<String> reasons = new ArrayList<>();
        assertFalse(ProgressionRuntime.parts().questGates().accepts(player, quest, reasons));
        assertTrue(reasons.contains("one"));
        assertTrue(reasons.contains("two"),
                "accepts must not short-circuit, or a player is told only the first of their"
                        + " problems and fixes it to no effect");
    }

    @Test
    void preSatisfiedProgressFoldsAsTheLargestAnswer() {
        ProgressionRuntime.registrar(CONSUMER).questGates(preSatisfied(3L));
        ProgressionRuntime.registrar(OTHER).questGates(preSatisfied(7L));

        Quest quest = Quest.builder("q").build();
        ObjectiveDef objective = ObjectiveDef.builder("x", "BREAK_BLOCK").target("Stone").build();
        assertEquals(7L, ProgressionRuntime.parts().questGates()
                .preSatisfiedAmount(player, quest, objective));
    }

    // ==================== system gates ====================

    @Test
    void aSystemNobodyGatedIsOpen() {
        assertTrue(ProgressionRuntime.systemEnabled(ProgressionSystem.QUEST, player));
        assertTrue(ProgressionRuntime.systemEnabled(ProgressionSystem.ACHIEVEMENT, player));
    }

    /**
     * Gates AND, and a refusal costs exactly the system it names. Registered in both orders,
     * because an owner switch whose answer depended on load order would be off on some boots.
     */
    @Test
    void systemGatesAndSoOneRefusalClosesThatSystemAlone() {
        ProgressionRuntime.registrar(CONSUMER)
                .systemGate((system, subject) -> system != ProgressionSystem.QUEST);
        ProgressionRuntime.registrar(OTHER).systemGate((system, subject) -> true);

        assertFalse(ProgressionRuntime.systemEnabled(ProgressionSystem.QUEST, player));
        assertTrue(ProgressionRuntime.systemEnabled(ProgressionSystem.ACHIEVEMENT, player),
                "the other system is untouched by a refusal aimed at quests");

        ProgressionRuntime.resetForTests();
        ProgressionRuntime.registrar(OTHER).systemGate((system, subject) -> true);
        ProgressionRuntime.registrar(CONSUMER)
                .systemGate((system, subject) -> system != ProgressionSystem.QUEST);

        assertFalse(ProgressionRuntime.systemEnabled(ProgressionSystem.QUEST, player),
                "registration order cannot decide whether a system is on");
    }

    /**
     * A broken switch must not turn a whole system off for everybody in silence: it is read as
     * OPEN, it says so once, and the gates registered beside it still get their say.
     */
    @Test
    void aThrowingSystemGateIsReadAsOpenAndSaysSo() {
        List<String> warnings = new ArrayList<>();
        ProgressionRuntime.registrar(LIBRARY).warn(warnings::add);
        ProgressionRuntime.registrar(CONSUMER).systemGate((system, subject) -> {
            throw new IllegalStateException("boom");
        });

        assertTrue(ProgressionRuntime.systemEnabled(ProgressionSystem.QUEST, player));
        assertEquals(1, warnings.size(), "one warn for the one gate that failed");
        assertTrue(warnings.get(0).contains("quest"), warnings.get(0));

        ProgressionRuntime.registrar(OTHER)
                .systemGate((system, subject) -> system != ProgressionSystem.QUEST);
        assertFalse(ProgressionRuntime.systemEnabled(ProgressionSystem.QUEST, player),
                "a throwing neighbour must not swallow a gate that answered");
    }

    /**
     * The switch reaches an ACCEPT, not only a produced moment. An auto-accept is an accept the
     * player never asked for, so a server with quests off must not hand one out at login and then
     * refuse every moment that would have advanced it; and a deliberate accept asked of the same
     * switched-off system is told no with the engine's own reason rather than quietly taken.
     */
    @Test
    void anAutoAcceptQuestIsNotAcceptedForAPlayerWhoseQuestsAreSwitchedOff() {
        Subject other = Subject.of(UUID.randomUUID(), "other");
        ProgressionRuntime.registrar(CONSUMER).systemGate((system, subject) ->
                !(system == ProgressionSystem.QUEST && subject.id().equals(player.id())));
        Quest tutorial = Quest.builder("tutorial")
                .objective(ObjectiveDef.builder("x", "BREAK_BLOCK").target("Oak_Log")
                        .matchMode(MatchMode.EXACT).amount(1).build())
                .autoAccept(true)
                .build();
        ProgressionRuntime.publishQuests(CONSUMER, List.of(tutorial));
        QuestEngine engine = ProgressionRuntime.quests();

        assertEquals(0, engine.autoAcceptAvailable(player),
                "quests are off for this player, so nothing is accepted on their behalf");
        assertEquals(QuestStatus.NOT_STARTED, engine.status(player, tutorial));
        QuestEngine.AcceptCheck refused = engine.canAccept(player, tutorial);
        assertFalse(refused.allowed());
        assertEquals(List.of(QuestGates.REASON_SYSTEM_DISABLED), refused.reasons(),
                "the engine's own reason, standing alone");

        assertEquals(1, engine.autoAcceptAvailable(other),
                "the same gate answers per player, and this one has quests on");
        assertEquals(QuestStatus.ACTIVE, engine.status(other, tutorial));
    }

    @Test
    void anAutoAcceptQuestIsAcceptedWhenNoSystemGateIsRegistered() {
        Quest tutorial = Quest.builder("tutorial")
                .objective(ObjectiveDef.builder("x", "BREAK_BLOCK").target("Oak_Log")
                        .matchMode(MatchMode.EXACT).amount(1).build())
                .autoAccept(true)
                .build();
        ProgressionRuntime.publishQuests(CONSUMER, List.of(tutorial));
        QuestEngine engine = ProgressionRuntime.quests();

        assertTrue(engine.canAccept(player, tutorial).allowed());
        assertEquals(1, engine.autoAcceptAvailable(player),
                "a runtime nobody switched off accepts on the player's behalf");
        assertEquals(QuestStatus.ACTIVE, engine.status(player, tutorial));
    }

    /**
     * The achievement side of the same rule. The maintenance pass EARNS whatever it finds met, so a
     * login on a server with achievements switched off would otherwise hand out every achievement
     * the player already qualifies for; the switch refuses that pass exactly as it refuses a moment.
     */
    @Test
    void theAchievementSelfHealEarnsNothingForAPlayerWhoseAchievementsAreSwitchedOff() {
        Subject other = Subject.of(UUID.randomUUID(), "other");
        ProgressionRuntime.registrar(CONSUMER).systemGate((system, subject) ->
                !(system == ProgressionSystem.ACHIEVEMENT && subject.id().equals(player.id())));
        // Held back at unlock time so the criterion is MET but not yet earned: that is the state
        // self-heal exists to revisit, and the one this test needs it to be asked about.
        Set<String> heldBack = new HashSet<>(Set.of("first_ore"));
        ProgressionRuntime.registrar(CONSUMER).achievementGates(new AchievementGates() {
            @Override
            public boolean canUnlock(@Nonnull Subject subject, @Nonnull Achievement achievement) {
                return !heldBack.contains(achievement.id());
            }
        });
        Achievement firstOre = Achievement.builder("first_ore")
                .criterion(ObjectiveDef.builder("0", "BREAK_BLOCK").target("Copper_Ore")
                        .matchMode(MatchMode.EXACT).amount(1).build())
                .build();
        ProgressionRuntime.publishAchievements(CONSUMER, List.of(firstOre));
        AchievementEngine engine = ProgressionRuntime.achievements();
        engine.dispatch(player, "BREAK_BLOCK", "Copper_Ore", null, 1L);
        engine.dispatch(other, "BREAK_BLOCK", "Copper_Ore", null, 1L);
        heldBack.clear();

        assertEquals(0, engine.selfHeal(player),
                "achievements are off for this player, so the pass earns nothing for them");
        assertFalse(engine.isUnlocked(player, "first_ore"));

        assertEquals(1, engine.selfHeal(other), "and on for this one, so the met criterion is earned");
        assertTrue(engine.isUnlocked(other, "first_ore"));
    }

    @Test
    void aThrowingTapCostsOnlyItsOwnObservation() {
        List<String> seen = new ArrayList<>();
        ProgressionRuntime.registrar(CONSUMER).dispatchTap(
                (subject, kind, target, qualifier, amount, zone) -> {
                    throw new IllegalStateException("boom");
                });
        ProgressionRuntime.registrar(OTHER).dispatchTap(
                (subject, kind, target, qualifier, amount, zone) -> seen.add(kind));

        ProgressionRuntime.parts().tap().observe(player, "BREAK_BLOCK", "Stone", null, 1L, null);
        assertEquals(List.of("BREAK_BLOCK"), seen);
    }

    @Test
    void textSourcesAnswerInRegistrationOrderAndNullMeansPass() {
        ProgressionRuntime.registrar(CONSUMER).textSource(new StubText(null));
        ProgressionRuntime.registrar(OTHER).textSource(new StubText("second"));

        assertEquals(2, ProgressionRuntime.textSources().size());
        assertEquals("second", ((StubText) ProgressionRuntime.textSources().get(1)).answer);
    }

    /**
     * Content takes no claim of its own, so RANK is the whole of what decides whose reading of a
     * shared file the player gets. Which makes a registrar's own rank worth reading back: a mod that
     * meant to register as a consumer and registered as a library default becomes the library's peer
     * rather than its replacement, and every id they share turns from a silent hand-off into a
     * reported clash.
     */
    @Test
    void aRegistrarsRankIsReadableBackBecauseContentResolutionRestsOnIt() {
        ProgressionRuntime.defaults(LIBRARY);
        ProgressionRuntime.registrar(CONSUMER);

        assertTrue(ProgressionRuntime.registrars().contains(LIBRARY));
        assertTrue(ProgressionRuntime.registrars().contains(CONSUMER));
        assertTrue(ProgressionRuntime.isLibraryDefault(LIBRARY));
        assertFalse(ProgressionRuntime.isLibraryDefault(CONSUMER));
        assertFalse(ProgressionRuntime.isLibraryDefault("nobody_registered_this"),
                "a name nobody registered is not a library default either");
    }

    /**
     * The consumer's entry REPLACES the library default's for the same id, and says nothing about
     * it: that is the hand-off a content-ownership claim used to arrange by hand, done by rank
     * alone. Both layers publish everything they folded, and the merge sorts it out.
     */
    @Test
    void aConsumerLayerSilentlyReplacesALibraryDefaultsEntryForTheSameId() {
        List<String> warnings = new ArrayList<>();
        ProgressionRuntime.defaults(LIBRARY).warn(warnings::add);
        ProgressionRuntime.registrar(CONSUMER);

        ProgressionRuntime.publishQuests(LIBRARY, List.of(
                Quest.builder("shared").tag("library_reading").build(),
                Quest.builder("library_only").build()));
        ProgressionRuntime.publishQuests(CONSUMER, List.of(
                Quest.builder("shared").tag("consumer_reading").build()));

        QuestEngine engine = ProgressionRuntime.quests();
        assertTrue(engine.quest("shared").hasTag("consumer_reading"),
                "rank alone hands the id to the consumer");
        assertFalse(engine.quest("shared").hasTag("library_reading"),
                "and the library's entry for it is gone rather than merged into it");
        assertNotNull(engine.quest("library_only"),
                "and an id only the library folded still reaches the engines");
        assertTrue(warnings.isEmpty(), "a consumer outranking a default is the contract working,"
                + " so there is nothing to report: " + warnings);
    }

    // ==================== content layers ====================

    @Test
    void layersMergeWithTheConsumerWinningAndBothStillPresent() {
        ProgressionRuntime.defaults(LIBRARY);
        ProgressionRuntime.registrar(CONSUMER);
        ProgressionRuntime.publishQuests(LIBRARY, List.of(Quest.builder("shared").build(),
                Quest.builder("library_only").build()));
        ProgressionRuntime.publishQuests(CONSUMER, List.of(Quest.builder("shared").build(),
                Quest.builder("consumer_only").build()));

        QuestEngine engine = ProgressionRuntime.quests();
        assertEquals(3, engine.quests().size());
        assertNotNull(engine.quest("library_only"));
        assertNotNull(engine.quest("consumer_only"));
        assertNotNull(engine.quest("shared"));
    }

    @Test
    void publishingAgainReplacesThatOwnersLayerRatherThanAddingToIt() {
        ProgressionRuntime.publishQuests(CONSUMER, List.of(Quest.builder("first").build()));
        assertNotNull(ProgressionRuntime.quests().quest("first"));

        ProgressionRuntime.publishQuests(CONSUMER, List.of(Quest.builder("second").build()));
        assertEquals(1, ProgressionRuntime.quests().quests().size(),
                "a content reload replaces one owner's layer; anything else grows forever");
        assertNotNull(ProgressionRuntime.quests().quest("second"));
    }

    // ==================== what the engines were built over ====================

    @Test
    void theEnginesReadTheRegisteredPartsThroughTheirForwarders() {
        ProgressionRuntime.registrar(CONSUMER)
                .maxTrackedQuests(3)
                .maxActiveQuests(2);

        QuestEngine engine = ProgressionRuntime.quests();
        assertEquals(3, engine.maxTracked());
        assertEquals(2, engine.maxActive());

        // Registered AFTER the build: the forwarder makes it live all the same, and the engine's
        // own store handle keeps answering through it rather than through whatever it was built on.
        QuestProgressStore late = new InMemoryQuestProgressStore();
        ProgressionRuntime.registrar(CONSUMER).questStore(late);
        engine.store().setCooldownStamp(player, "q", 1234L);
        assertEquals(1234L, late.cooldownStamp(player, "q"),
                "the engine writes through to whichever store is registered right now");
    }

    @Test
    void theEngineInstancesAreStableAcrossEveryLaterRegistration() {
        QuestEngine before = ProgressionRuntime.quests();
        ProgressionRuntime.registrar(CONSUMER).questStore(new InMemoryQuestProgressStore());
        ProgressionRuntime.publishQuests(CONSUMER, List.of(Quest.builder("q").build()));

        assertSame(before, ProgressionRuntime.quests(),
                "a rebuild would orphan every cached reference, which one shared runtime cannot"
                        + " afford");
    }

    // ==================== fixtures ====================

    @Nonnull
    private static QuestGates refusing(@Nonnull String reason) {
        return new QuestGates() {

            @Override
            public boolean accepts(@Nonnull Subject subject, @Nonnull Quest quest,
                                   @Nonnull List<String> reasons) {
                reasons.add(reason);
                return false;
            }
        };
    }

    @Nonnull
    private static QuestGates preSatisfied(long amount) {
        return new QuestGates() {

            @Override
            public long preSatisfiedAmount(@Nonnull Subject subject, @Nonnull Quest quest,
                                           @Nonnull ObjectiveDef objective) {
                return amount;
            }
        };
    }

    /**
     * A text source that knows nothing. Building a real {@code Message} needs the engine's own
     * factory, which is not what this case is about: what is pinned is that both sources are kept,
     * in registration order, so the walk that asks them in turn has both to ask.
     */
    private static final class StubText implements ProgressionTextSource {

        private final String answer;

        StubText(String answer) {
            this.answer = answer;
        }

        @Override
        public Message title(@Nonnull String contentId) {
            return null;
        }

        @Override
        public Message flavor(@Nonnull String contentId) {
            return null;
        }

        @Override
        public Message objective(@Nonnull String contentId, @Nonnull String objectiveId) {
            return null;
        }
    }
}
