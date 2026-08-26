package com.ziggfreed.common.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.progress.MatchMode;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.ObjectiveProgressState;
import com.ziggfreed.common.subject.Subject;

/**
 * The narrow read seam a conversation is handed: it has to answer every question that surface asks
 * WITHOUT the engine, and it has to stay total - an id that no longer exists is a content edit, not
 * an exception, and the two directions it falls back in are deliberately opposite.
 *
 * <p>Every assertion here goes through a {@link QuestStateReader}-typed reference on purpose. If a
 * read a conversation needs ever stops being reachable that way, this stops compiling, which is the
 * whole point of the seam.
 */
class QuestStateReaderTest {

    private Subject player;
    private Map<String, Integer> held;
    private QuestEngine engine;
    private QuestStateReader reader;

    @BeforeEach
    void setUp() {
        player = Subject.of(UUID.randomUUID(), "tester");
        held = new HashMap<>();
        engine = QuestEngine.builder()
                .store(new InMemoryQuestProgressStore())
                .possessionProbe((itemId, count) -> held.getOrDefault(itemId, 0) >= count)
                .nativeEvents(false)
                .warn(message -> { })
                .build();
        engine.setQuests(List.of(gather(), handIn()));
        reader = engine;
    }

    /** A plain collect quest, parked so it can sit in the unclaimed state. */
    @Nonnull
    private static Quest gather() {
        return Quest.builder("q_gather")
                .objective(ObjectiveDef.builder("logs", "BREAK_BLOCK")
                        .target("Oak_Log").matchMode(MatchMode.EXACT).amount(2).build())
                .reward(RewardSpec.of("NOTE", "text", "parked"))
                .build();
    }

    /** A hand-in locked to one place, so "here" and "somewhere else" can be told apart. */
    @Nonnull
    private static Quest handIn() {
        return Quest.builder("q_deliver")
                .objective(ObjectiveDef.builder("bring_logs", "TURN_IN")
                        .target("Oak_Log").matchMode(MatchMode.EXACT).amount(3)
                        .turnInLockId("keeper").build())
                .build();
    }

    // ==================== Status ====================

    @Test
    void anUnknownQuestIdReadsAsNotStartedRatherThanThrowing() {
        assertEquals(QuestStatus.NOT_STARTED, reader.status(player, "q_deleted_last_patch"));
    }

    @Test
    void statusByIdAgreesWithTheEngineThroughTheWholeLifecycle() {
        Quest quest = engine.quest("q_gather");
        assertEquals(QuestStatus.NOT_STARTED, reader.status(player, "q_gather"));

        engine.accept(player, quest);
        assertEquals(QuestStatus.ACTIVE, reader.status(player, "q_gather"));

        engine.dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 2);
        assertEquals(QuestStatus.COMPLETED_UNCLAIMED, reader.status(player, "q_gather"));

        engine.claim(player, quest);
        assertEquals(QuestStatus.COMPLETED, reader.status(player, "q_gather"));
    }

    // ==================== Progress ====================

    @Test
    void objectiveProgressIsNullUntilThereIsSomethingToReport() {
        assertNull(reader.objectiveProgress(player, "q_gather", "logs"));

        engine.accept(player, engine.quest("q_gather"));
        engine.dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 1);

        ObjectiveProgressState state = reader.objectiveProgress(player, "q_gather", "logs");
        assertEquals(1, state == null ? -1 : state.current());
        assertEquals(2, state == null ? -1 : state.required());
    }

    @Test
    void progressForAnUnknownQuestOrStepIsNullRatherThanAnError() {
        assertNull(reader.objectiveProgress(player, "q_deleted_last_patch", "logs"));
        assertNull(reader.objectiveProgress(player, "q_gather", "step_that_moved"));
    }

    // ==================== What is in progress ====================

    @Test
    void activeAndUnclaimedIdsCoversBothCarriedAndCollectableQuests() {
        assertTrue(reader.activeAndUnclaimedIds(player).isEmpty());

        engine.accept(player, engine.quest("q_gather"));
        engine.accept(player, engine.quest("q_deliver"));
        List<String> carried = reader.activeAndUnclaimedIds(player);
        assertEquals(2, carried.size());
        assertTrue(carried.containsAll(List.of("q_gather", "q_deliver")));

        engine.dispatch(player, "BREAK_BLOCK", "Oak_Log", null, 2);
        assertTrue(reader.activeAndUnclaimedIds(player).contains("q_gather"),
                "a quest waiting to be collected is still in progress as far as a reader is concerned");

        engine.claim(player, engine.quest("q_gather"));
        assertEquals(List.of("q_deliver"), reader.activeAndUnclaimedIds(player));
    }

    // ==================== Hand-in readiness ====================

    @Test
    void aHandInIsOfferableOnlyWhereItIsLockedAndOnlyWithTheGoods() {
        engine.accept(player, engine.quest("q_deliver"));

        assertFalse(reader.canDeliverTurnInAt(player, "q_deliver", "keeper"),
                "an empty pack must never be offered a hand-in");

        held.put("Oak_Log", 3);
        assertTrue(reader.canDeliverTurnInAt(player, "q_deliver", "keeper"));
        assertFalse(reader.canDeliverTurnInAt(player, "q_deliver", "somebody_else"));
        assertFalse(reader.canDeliverTurnInAt(player, "q_deliver", null));
    }

    @Test
    void aPartialStackIsNotDeliverable() {
        engine.accept(player, engine.quest("q_deliver"));
        held.put("Oak_Log", 2);

        assertFalse(reader.canDeliverTurnInAt(player, "q_deliver", "keeper"),
                "offering a hand-in that only half finishes the step is the bug this guards");
    }

    @Test
    void anUnknownQuestIdFailsClosedOnReadiness() {
        held.put("Oak_Log", 64);
        assertFalse(reader.canDeliverTurnInAt(player, "q_deleted_last_patch", "keeper"));
    }

    @Test
    void theAnyQuestFormFindsWhateverCanBeHandedInHere() {
        assertFalse(reader.hasDeliverableTurnInAt(player, "keeper"),
                "nothing accepted, nothing to hand in");

        engine.accept(player, engine.quest("q_deliver"));
        held.put("Oak_Log", 3);

        assertTrue(reader.hasDeliverableTurnInAt(player, "keeper"));
        assertFalse(reader.hasDeliverableTurnInAt(player, "somebody_else"));
    }
}
