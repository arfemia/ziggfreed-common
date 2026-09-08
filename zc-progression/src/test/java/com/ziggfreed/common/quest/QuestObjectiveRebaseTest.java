package com.ziggfreed.common.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.progress.MatchMode;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.ObjectiveKindRegistry;
import com.ziggfreed.common.progress.ObjectiveProgressState;
import com.ziggfreed.common.subject.Subject;

/**
 * What happens to a player already carrying a quest when its step is re-tuned underneath them.
 *
 * <p>Progress persists as {@code current/required}, so without a rebase the target a save remembers
 * outlives the asset it came from, and the step's drawn sentence (which reads the live asset) stops
 * agreeing with its own count. Every number here is authored by the test, never read from shipped
 * content, so a balancing pass never has to touch this file.
 */
class QuestObjectiveRebaseTest {

    private InMemoryQuestProgressStore store;
    private Subject player;

    @BeforeEach
    void setUp() {
        store = new InMemoryQuestProgressStore();
        player = Subject.of(UUID.randomUUID(), "tester");
    }

    @Nonnull
    private QuestEngine engineOver(@Nonnull Quest quest) {
        // Both kinds this fixture authors have to be part of the engine's vocabulary, or dispatch
        // refuses them and every count below would read zero for a reason that is not the rebase.
        ObjectiveKindRegistry kinds = new ObjectiveKindRegistry();
        kinds.register("STATION_OUTPUT");
        kinds.register("TURN_IN");
        QuestEngine engine = QuestEngine.builder()
                .store(store)
                .objectiveKinds(kinds)
                .nativeEvents(false)
                .warn(message -> { })
                .build();
        engine.setQuests(List.of(quest));
        return engine;
    }

    @Nonnull
    private static Quest millQuest(long amount) {
        return Quest.builder("q_mill")
                .objective(ObjectiveDef.builder("volume", "STATION_OUTPUT")
                        .target("Wood_").matchMode(MatchMode.PREFIX).amount(amount).build())
                .build();
    }

    @Test
    void raisingTheAmountRetargetsAQuestAlreadyInProgress() {
        QuestEngine before = engineOver(millQuest(1_000));
        assertTrue(before.accept(player, before.quest("q_mill")));
        before.dispatch(player, "STATION_OUTPUT", "Wood_Oak", null, 510);

        QuestEngine after = engineOver(millQuest(10_000));
        ObjectiveProgressState state = after.progressOf(player, "q_mill", "volume");

        assertEquals(10_000, state.required(), "the count must aim at what the step asks for now");
        assertEquals(510, state.current(), "and keep every piece the player already milled");
        assertFalse(state.isCompleted());
    }

    @Test
    void loweringTheAmountUnderWhatIsDoneFinishesTheStep() {
        QuestEngine before = engineOver(millQuest(1_000));
        assertTrue(before.accept(player, before.quest("q_mill")));
        before.dispatch(player, "STATION_OUTPUT", "Wood_Oak", null, 510);

        QuestEngine after = engineOver(millQuest(500));
        ObjectiveProgressState state = after.progressOf(player, "q_mill", "volume");

        assertEquals(500, state.required());
        assertEquals(500, state.current(), "a count past the new target reads as the target, not over it");
        assertTrue(state.isCompleted());
    }

    @Test
    void aStepAlreadyFinishedStaysFinishedWhenTheAmountClimbs() {
        QuestEngine before = engineOver(millQuest(1_000));
        assertTrue(before.accept(player, before.quest("q_mill")));
        before.dispatch(player, "STATION_OUTPUT", "Wood_Oak", null, 1_000);
        assertTrue(before.progressOf(player, "q_mill", "volume").isCompleted());

        QuestEngine after = engineOver(millQuest(10_000));
        ObjectiveProgressState state = after.progressOf(player, "q_mill", "volume");

        assertTrue(state.isCompleted(), "work already done is never taken back");
        assertEquals(10_000, state.current());
        assertEquals(10_000, state.required());
    }

    @Test
    void anObjectiveTheQuestNoLongerDeclaresKeepsWhatWasStored() {
        QuestEngine before = engineOver(millQuest(1_000));
        assertTrue(before.accept(player, before.quest("q_mill")));
        before.dispatch(player, "STATION_OUTPUT", "Wood_Oak", null, 510);

        Quest rewritten = Quest.builder("q_mill")
                .objective(ObjectiveDef.builder("sap", "TURN_IN")
                        .target("Ingredient_Tree_Sap").matchMode(MatchMode.EXACT).amount(20).build())
                .build();
        QuestEngine after = engineOver(rewritten);
        ObjectiveProgressState orphan = after.progressOf(player, "q_mill", "volume");

        assertEquals(510, orphan.current(), "nothing to size it against, so nothing is changed");
        assertEquals(1_000, orphan.required());
    }
}
