package com.ziggfreed.common.objectives.producer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.AchievementEngine;
import com.ziggfreed.common.achievement.InMemoryAchievementProgressStore;
import com.ziggfreed.common.progress.DispatchOptions;
import com.ziggfreed.common.progress.MatchMode;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.ObjectiveProgressState;
import com.ziggfreed.common.progress.ZoneRef;
import com.ziggfreed.common.progress.runtime.Moment;
import com.ziggfreed.common.progress.runtime.MomentPayload;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.progress.runtime.ProgressionSubjectSource;
import com.ziggfreed.common.progress.runtime.ProgressionSystem;
import com.ziggfreed.common.quest.InMemoryQuestProgressStore;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.subject.Subject;

/**
 * What a producer actually does when it fires: one moment reaches every registered REACTION and
 * BOTH engines, and each engine advances whatever it has authored against that kind.
 *
 * <p>Driven through the moment-facing and engine-facing halves of {@link ProgressDispatch}, so the
 * mechanism under test is the real one while the ECS half - which only turns an event into a store,
 * a ref and a {@code PlayerRef} - stays for in-game smoke. Both engines run over an in-memory store
 * with native events off; there is no event bus in a unit JVM. A hand-built {@link Moment} carries
 * a store-less ref and no store at all: the record's entity handles are what a REACTION writes
 * against, and nothing under test here dereferences them.
 */
class ProgressDispatchTest {

    private static final String BREAK_BLOCK = "BREAK_BLOCK";
    private static final String KILL_ENTITY = "KILL_ENTITY";

    /** A stand-in for a producer's own record, so a listener can be shown reading one. */
    private record TestPayload(@Nonnull String note) implements MomentPayload {
    }

    private Subject player;
    private QuestEngine quests;
    private AchievementEngine achievements;

    @BeforeEach
    void setUp() {
        // The dispatch reads the shared runtime for the call scopes and the owner system switches,
        // so each case starts from a runtime nobody has registered anything into.
        ProgressionRuntime.resetForTests();
        player = Subject.of(UUID.randomUUID(), "tester");
        quests = QuestEngine.builder()
                .store(new InMemoryQuestProgressStore())
                .nativeEvents(false)
                .warn(message -> { })
                .build();
        achievements = AchievementEngine.builder()
                .store(new InMemoryAchievementProgressStore())
                .nativeEvents(false)
                .warn(message -> { })
                .build();
    }

    @AfterEach
    void tearDown() {
        ProgressionRuntime.resetForTests();
    }

    @Nonnull
    private static ObjectiveDef objective(@Nonnull String id, @Nonnull String kind,
            @Nonnull String target, long amount) {
        return ObjectiveDef.builder(id, kind)
                .target(target).matchMode(MatchMode.EXACT).amount(amount).build();
    }

    @Test
    void oneFiredMomentReachesBothEngines() {
        Quest quest = Quest.builder("q_gather")
                .objective(objective("logs", BREAK_BLOCK, "Oak_Log", 3))
                .build();
        Achievement achievement = Achievement.builder("a_lumberjack")
                .criterion(objective("0", BREAK_BLOCK, "Oak_Log", 5))
                .build();
        quests.setQuests(List.of(quest));
        achievements.setAchievements(List.of(achievement));
        assertTrue(quests.accept(player, quest));

        ProgressDispatch.dispatch(quests, achievements, player, player, BREAK_BLOCK, "Oak_Log", null, 2L, null);

        assertNotNull(quests.progressOf(player, "q_gather", "logs"));
        assertEquals(2, quests.progressOf(player, "q_gather", "logs").current());
        assertEquals(2, achievements.progressOf(player, achievement, 0).current());
    }

    /**
     * One action, one tap. Both engines are built over the SAME composed tap, so a produced moment
     * that told it twice would hand every lifetime counter on the server two of every block broken.
     */
    @Test
    void oneFiredMomentIsSeenByTheTapExactlyOnce() {
        List<String> seen = new ArrayList<>();
        quests = QuestEngine.builder()
                .store(new InMemoryQuestProgressStore())
                .nativeEvents(false)
                .warn(message -> { })
                .dispatchTap((subject, kind, target, qualifier, amount, zone) -> seen.add("quest:" + kind))
                .build();
        achievements = AchievementEngine.builder()
                .store(new InMemoryAchievementProgressStore())
                .nativeEvents(false)
                .warn(message -> { })
                .dispatchTap((subject, kind, target, qualifier, amount, zone) -> seen.add("achievement:" + kind))
                .build();

        ProgressDispatch.dispatch(quests, achievements, player, player, BREAK_BLOCK, "Oak_Log", null, 1L, null);

        assertEquals(List.of("quest:" + BREAK_BLOCK), seen,
                "the first half that runs carries the tap and the second stays out of it");
    }

    /** The half that runs is the half that taps, so a server keeping only one still counts. */
    @Test
    void theRemainingHalfCarriesTheTapWhenTheOtherIsSkipped() {
        List<String> seen = new ArrayList<>();
        achievements = AchievementEngine.builder()
                .store(new InMemoryAchievementProgressStore())
                .nativeEvents(false)
                .warn(message -> { })
                .dispatchTap((subject, kind, target, qualifier, amount, zone) -> seen.add("achievement:" + kind))
                .build();

        ProgressDispatch.dispatch(quests, achievements, null, player, BREAK_BLOCK, "Oak_Log", null, 1L, null);

        assertEquals(List.of("achievement:" + BREAK_BLOCK), seen);
    }

    @Test
    void aMomentNothingIsWaitingOnChangesNothing() {
        Quest quest = Quest.builder("q_gather")
                .objective(objective("logs", BREAK_BLOCK, "Oak_Log", 3))
                .build();
        quests.setQuests(List.of(quest));
        assertTrue(quests.accept(player, quest));

        ProgressDispatch.dispatch(quests, achievements, player, player, KILL_ENTITY, "Oak_Log", null, 4L, null);
        ProgressDispatch.dispatch(quests, achievements, player, player, BREAK_BLOCK, "Birch_Log", null, 4L, null);

        ObjectiveProgressState state = quests.progressOf(player, "q_gather", "logs");
        assertTrue(state == null || state.current() == 0,
                "a different kind and a different target are both simply not this objective");
        assertEquals(QuestStatus.ACTIVE, quests.status(player, quest));
    }

    @Test
    void enoughFiredMomentsFinishTheQuest() {
        Quest quest = Quest.builder("q_gather")
                .objective(objective("logs", BREAK_BLOCK, "Oak_Log", 3))
                .autoClaim(false)
                .build();
        quests.setQuests(List.of(quest));
        assertTrue(quests.accept(player, quest));

        ProgressDispatch.dispatch(quests, achievements, player, player, BREAK_BLOCK, "Oak_Log", null, 3L, null);

        assertEquals(QuestStatus.COMPLETED_UNCLAIMED, quests.status(player, quest));
    }

    /**
     * A zone-scoped objective is the one shape that a dispatch can silently switch OFF rather than
     * merely make less precise: with no zone on the event it never matches, and nothing anywhere
     * says so. So the zone has to survive the whole way from the producer to the matcher.
     */
    @Test
    void aZoneScopedObjectiveAdvancesOnlyWhenTheMomentCarriesThatZone() {
        Quest quest = Quest.builder("q_sands")
                .objective(ObjectiveDef.builder("snakes", KILL_ENTITY)
                        .target("Sand_Snake").matchMode(MatchMode.EXACT).amount(3)
                        .zone("Howling_Sands").build())
                .build();
        quests.setQuests(List.of(quest));
        assertTrue(quests.accept(player, quest));

        ProgressDispatch.dispatch(quests, achievements, player, player, KILL_ENTITY, "Sand_Snake",
                null, 1L, null);
        ObjectiveProgressState unlocated = quests.progressOf(player, "q_sands", "snakes");
        assertTrue(unlocated == null || unlocated.current() == 0,
                "a kill with nowhere attached can never satisfy a zone-scoped step");

        ProgressDispatch.dispatch(quests, achievements, player, player, KILL_ENTITY, "Sand_Snake",
                null, 1L, new ZoneRef("Howling_Sands", "Sands"));

        assertEquals(1, quests.progressOf(player, "q_sands", "snakes").current(),
                "and the same kill in the authored zone does");
    }

    /** Each engine is handed the subject ITS OWN store understands, so one may be absent. */
    @Test
    void aHalfWithNoSubjectIsSkippedWhileTheOtherStillLands() {
        Achievement achievement = Achievement.builder("a_lumberjack")
                .criterion(objective("0", BREAK_BLOCK, "Oak_Log", 5))
                .build();
        Quest quest = Quest.builder("q_gather")
                .objective(objective("logs", BREAK_BLOCK, "Oak_Log", 3))
                .build();
        quests.setQuests(List.of(quest));
        achievements.setAchievements(List.of(achievement));
        assertTrue(quests.accept(player, quest));

        ProgressDispatch.dispatch(quests, achievements, null, player, BREAK_BLOCK, "Oak_Log",
                null, 1L, null);

        ObjectiveProgressState quiet = quests.progressOf(player, "q_gather", "logs");
        assertTrue(quiet == null || quiet.current() == 0,
                "no quest subject, no quest write - the owner has that half switched off");
        assertEquals(1, achievements.progressOf(player, achievement, 0).current(),
                "and the achievement half is untouched by that");
    }

    // ==================== the owner's system switches ====================

    /**
     * An owner who has switched quests off for a player still has them off when the moment is
     * produced by a shared producer. It costs exactly that half: the achievement side of the same
     * action lands untouched, which is what makes this a per-system gate rather than a veto.
     */
    @Test
    void aRefusingSystemGateSkipsThatHalfAndLeavesTheOtherAlone() {
        ProgressionRuntime.registrar("othermod").systemGate((system, subject) -> true);
        ProgressionRuntime.registrar("yourmod")
                .systemGate((system, subject) -> system != ProgressionSystem.QUEST);

        Quest quest = Quest.builder("q_gather")
                .objective(objective("logs", BREAK_BLOCK, "Oak_Log", 3))
                .build();
        Achievement achievement = Achievement.builder("a_lumberjack")
                .criterion(objective("0", BREAK_BLOCK, "Oak_Log", 5))
                .build();
        quests.setQuests(List.of(quest));
        achievements.setAchievements(List.of(achievement));
        assertTrue(quests.accept(player, quest));

        ProgressDispatch.dispatch(quests, achievements, player, player, BREAK_BLOCK, "Oak_Log",
                null, 1L, null);

        ObjectiveProgressState quiet = quests.progressOf(player, "q_gather", "logs");
        assertTrue(quiet == null || quiet.current() == 0,
                "the owner has quests off for this player, so nothing is written to that engine");
        assertEquals(1, achievements.progressOf(player, achievement, 0).current(),
                "and the half the switch says nothing about is untouched");
    }

    /** Nobody registered a switch, so every system is on: the bare-server reading. */
    @Test
    void withNoSystemGateBothHalvesLand() {
        Quest quest = Quest.builder("q_gather")
                .objective(objective("logs", BREAK_BLOCK, "Oak_Log", 3))
                .build();
        Achievement achievement = Achievement.builder("a_lumberjack")
                .criterion(objective("0", BREAK_BLOCK, "Oak_Log", 5))
                .build();
        quests.setQuests(List.of(quest));
        achievements.setAchievements(List.of(achievement));
        assertTrue(quests.accept(player, quest));

        ProgressDispatch.dispatch(quests, achievements, player, player, BREAK_BLOCK, "Oak_Log",
                null, 1L, null);

        assertEquals(1, quests.progressOf(player, "q_gather", "logs").current());
        assertEquals(1, achievements.progressOf(player, achievement, 0).current());
    }

    /**
     * A switch that THROWS is read as open, so a bug in one owner's config read can never turn a
     * whole system off for everybody. The dispatch itself is unaffected either way.
     */
    @Test
    void aThrowingSystemGateStillLetsBothHalvesLand() {
        ProgressionRuntime.registrar("yourmod").systemGate((system, subject) -> {
            throw new IllegalStateException("boom");
        });

        Quest quest = Quest.builder("q_gather")
                .objective(objective("logs", BREAK_BLOCK, "Oak_Log", 3))
                .build();
        quests.setQuests(List.of(quest));
        assertTrue(quests.accept(player, quest));

        ProgressDispatch.dispatch(quests, achievements, player, player, BREAK_BLOCK, "Oak_Log",
                null, 1L, null);

        assertEquals(1, quests.progressOf(player, "q_gather", "logs").current());
    }

    /**
     * One action, one tap, in the branch where a system switch is what skipped the quest half. The
     * tap is how a lifetime counter counts an action, and an owner turning quests off must not
     * quietly stop that counting.
     */
    @Test
    void theTapIsSpentExactlyOnceWhenASystemGateSkipsTheQuestHalf() {
        ProgressionRuntime.registrar("yourmod")
                .systemGate((system, subject) -> system != ProgressionSystem.QUEST);
        List<String> seen = new ArrayList<>();
        quests = QuestEngine.builder()
                .store(new InMemoryQuestProgressStore())
                .nativeEvents(false)
                .warn(message -> { })
                .dispatchTap((subject, kind, target, qualifier, amount, zone) -> seen.add("quest:" + kind))
                .build();
        achievements = AchievementEngine.builder()
                .store(new InMemoryAchievementProgressStore())
                .nativeEvents(false)
                .warn(message -> { })
                .dispatchTap((subject, kind, target, qualifier, amount, zone) -> seen.add("achievement:" + kind))
                .build();

        ProgressDispatch.dispatch(quests, achievements, player, player, BREAK_BLOCK, "Oak_Log", null, 1L, null);

        assertEquals(List.of("achievement:" + BREAK_BLOCK), seen,
                "the half that actually ran carries the tap, so the action is still counted once");
    }

    /** Both systems off for this player: nothing runs, so nothing taps either. */
    @Test
    void nothingTapsWhenEverySystemIsSwitchedOff() {
        ProgressionRuntime.registrar("yourmod").systemGate((system, subject) -> false);
        List<String> seen = new ArrayList<>();
        quests = QuestEngine.builder()
                .store(new InMemoryQuestProgressStore())
                .nativeEvents(false)
                .warn(message -> { })
                .dispatchTap((subject, kind, target, qualifier, amount, zone) -> seen.add("quest:" + kind))
                .build();
        achievements = AchievementEngine.builder()
                .store(new InMemoryAchievementProgressStore())
                .nativeEvents(false)
                .warn(message -> { })
                .dispatchTap((subject, kind, target, qualifier, amount, zone) -> seen.add("achievement:" + kind))
                .build();

        ProgressDispatch.dispatch(quests, achievements, player, player, BREAK_BLOCK, "Oak_Log", null, 1L, null);

        assertEquals(List.of(), seen);
    }

    @Test
    void anAbsentEngineIsSkippedRatherThanThrown() {
        Achievement achievement = Achievement.builder("a_lumberjack")
                .criterion(objective("0", BREAK_BLOCK, "Oak_Log", 5))
                .build();
        achievements.setAchievements(List.of(achievement));

        ProgressDispatch.dispatch(null, achievements, player, player, BREAK_BLOCK, "Oak_Log", null, 1L, null);

        assertEquals(1, achievements.progressOf(player, achievement, 0).current(),
                "a half-built runtime still feeds the half that exists");
    }

    // ==================== the reactions ====================

    /** A hand-built moment for the reaction half; the entity handles are never dereferenced. */
    @Nonnull
    private static Moment moment(@Nonnull String kind, @Nonnull String target, long amount,
            @Nullable Subject questSubject, @Nullable Subject achievementSubject,
            @Nullable MomentPayload payload) {
        return new Moment(kind, target, null, amount, null, (Store<EntityStore>) null,
                new Ref<EntityStore>((Store<EntityStore>) null), null, questSubject,
                achievementSubject, payload);
    }

    /**
     * A reaction is not a progression half. A player who has never opened a quest log has no
     * subject on either side, and the dispatch has nothing to write for them - but the block still
     * broke, and whoever awards XP for that must be told. So the listener sees the moment BEFORE
     * the one early return, and sees it whole: kind, target, amount, and the producer's payload.
     */
    @Test
    void aListenerSeesAMomentWhosePlayerHasNoSubjectOnEitherSide() {
        List<Moment> seen = new ArrayList<>();
        ProgressionRuntime.registrar("yourmod").momentListener(seen::add);

        ProgressDispatch.produce(quests, achievements,
                moment(BREAK_BLOCK, "Oak_Log", 1L, null, null, new TestPayload("the event")));

        assertEquals(1, seen.size(), "no subject means no engine write, never no reaction");
        Moment moment = seen.get(0);
        assertEquals(BREAK_BLOCK, moment.kindId());
        assertEquals("Oak_Log", moment.target());
        assertEquals(1L, moment.amount());
        assertNull(moment.questSubject());
        assertNull(moment.achievementSubject());
        assertNotNull(moment.payload(TestPayload.class), "the producer's own record rides along");
        assertEquals("the event", moment.payload(TestPayload.class).note());
    }

    /**
     * An owner who has switched BOTH systems off for a player has said nothing about XP. The
     * listener fires ahead of both system gates, so the moment still reaches every reaction while
     * neither engine is written to.
     */
    @Test
    void aListenerSeesAMomentBothSystemGatesRefuse() {
        ProgressionRuntime.registrar("yourmod").systemGate((system, subject) -> false);
        List<String> seen = new ArrayList<>();
        ProgressionRuntime.registrar("yourmod").momentListener(m -> seen.add(m.kindId()));
        Quest quest = Quest.builder("q_gather")
                .objective(objective("logs", BREAK_BLOCK, "Oak_Log", 3))
                .build();
        Achievement achievement = Achievement.builder("a_lumberjack")
                .criterion(objective("0", BREAK_BLOCK, "Oak_Log", 5))
                .build();
        quests.setQuests(List.of(quest));
        achievements.setAchievements(List.of(achievement));
        assertTrue(quests.accept(player, quest));

        ProgressDispatch.produce(quests, achievements,
                moment(BREAK_BLOCK, "Oak_Log", 1L, player, player, null));

        assertEquals(List.of(BREAK_BLOCK), seen, "the reaction ran");
        ObjectiveProgressState quiet = quests.progressOf(player, "q_gather", "logs");
        assertTrue(quiet == null || quiet.current() == 0, "and the quest half did not");
        ObjectiveProgressState quietToo = achievements.progressOf(player, achievement, 0);
        assertTrue(quietToo == null || quietToo.current() == 0, "nor the achievement half");
    }

    /**
     * One mod's broken reaction costs its own reaction and nobody else's - and never the engines'
     * half it was reacting beside.
     */
    @Test
    void aThrowingListenerCostsOnlyItself() {
        List<String> seen = new ArrayList<>();
        ProgressionRuntime.registrar("brokenmod").momentListener(m -> {
            throw new IllegalStateException("boom");
        });
        ProgressionRuntime.registrar("yourmod").momentListener(m -> seen.add(m.kindId()));
        Quest quest = Quest.builder("q_gather")
                .objective(objective("logs", BREAK_BLOCK, "Oak_Log", 3))
                .build();
        quests.setQuests(List.of(quest));
        assertTrue(quests.accept(player, quest));

        ProgressDispatch.produce(quests, achievements,
                moment(BREAK_BLOCK, "Oak_Log", 1L, player, player, null));

        assertEquals(List.of(BREAK_BLOCK), seen, "the listener after the broken one still ran");
        assertEquals(1, quests.progressOf(player, "q_gather", "logs").current(),
                "and the engines still saw the moment");
    }

    /**
     * Registration order is not a precedence: every listener sees every moment, so no listener can
     * mark a moment "handled" and starve the one registered after it - whichever way round.
     */
    @Test
    void everyListenerFiresWhateverTheRegistrationOrder() {
        List<String> seen = new ArrayList<>();
        ProgressionRuntime.registrar("first").momentListener(m -> seen.add("first"));
        ProgressionRuntime.registrar("second").momentListener(m -> seen.add("second"));
        ProgressDispatch.produce(quests, achievements,
                moment(BREAK_BLOCK, "Oak_Log", 1L, player, player, null));
        assertEquals(List.of("first", "second"), seen);

        ProgressionRuntime.resetForTests();
        seen.clear();
        ProgressionRuntime.registrar("second").momentListener(m -> seen.add("second"));
        ProgressionRuntime.registrar("first").momentListener(m -> seen.add("first"));
        ProgressDispatch.produce(quests, achievements,
                moment(BREAK_BLOCK, "Oak_Log", 1L, player, player, null));
        assertEquals(List.of("second", "first"), seen,
                "both fire either way; only the order they are told in follows registration");
    }

    /**
     * A listener registered after the runtime was built still fires: the dispatch reads the
     * composed fan-out through a live forwarder, so late registration is honoured on the next
     * moment rather than needing a rebuild.
     */
    @Test
    void aListenerRegisteredLateStillFires() {
        ProgressionRuntime.ensureBuilt();
        List<String> seen = new ArrayList<>();
        ProgressionRuntime.registrar("latemod").momentListener(m -> seen.add(m.kindId()));

        ProgressDispatch.produce(quests, achievements,
                moment(KILL_ENTITY, "Sand_Snake", 1L, player, player, null));

        assertEquals(List.of(KILL_ENTITY), seen);
    }

    /**
     * The ALIAS route - the same action re-dispatched under a second id so content authored
     * against that spelling advances too - reaches the engines and NO listener. A reaction already
     * saw the action once; a second pass would pay its XP twice, count it twice, and hand a reaction
     * an aliased kind whose payload it cannot read. Both public routes are driven here, over the
     * runtime's own engines, so what is pinned is the entry point a consumer actually calls.
     */
    @Test
    void theAliasRouteReachesTheEnginesAndNoListener() {
        Ref<EntityStore> ref = new Ref<EntityStore>((Store<EntityStore>) null);
        ProgressionRuntime.registrar("yourmod")
                .subjects(new ProgressionSubjectSource() {
                    @Override
                    @Nullable
                    public Subject questSubject(@Nonnull Store<EntityStore> store,
                            @Nonnull Ref<EntityStore> r) {
                        return player;
                    }

                    @Override
                    @Nullable
                    public Subject achievementSubject(@Nonnull Store<EntityStore> store,
                            @Nonnull Ref<EntityStore> r) {
                        return player;
                    }
                })
                .warn(message -> { });
        List<Moment> seen = new ArrayList<>();
        ProgressionRuntime.registrar("yourmod").momentListener(seen::add);
        Quest quest = Quest.builder("q_gather")
                .objective(objective("logs", BREAK_BLOCK, "Wheat", 3))
                .build();
        ProgressionRuntime.publishQuests("yourmod", List.of(quest));
        QuestEngine runtimeQuests = ProgressionRuntime.quests();
        assertTrue(runtimeQuests.accept(player, quest));

        // The primary fire, the way a producer makes it: every reaction, then both engines.
        ProgressDispatch.fire(null, ref, null, "PICKUP_ITEM", "Wheat", null, 1L,
                new TestPayload("the pickup"));
        assertEquals(1, seen.size(), "the produced moment reached the reaction");
        assertEquals("PICKUP_ITEM", seen.get(0).kindId());
        assertSame(player, seen.get(0).questSubject(),
                "carrying the subject the runtime built for this player");

        // The alias, the way a reaction makes it: the same pickup credited as a block broken under
        // the crop's item id, engines only.
        ProgressDispatch.fire(null, ref, BREAK_BLOCK, "Wheat", null, 1L,
                DispatchOptions.OBJECTIVES_ONLY);

        assertEquals(1, seen.size(), "the alias reached NO listener, so nothing reacts twice");
        assertEquals(1, runtimeQuests.progressOf(player, "q_gather", "logs").current(),
                "and the content authored against the aliased kind still advanced");
    }
}
