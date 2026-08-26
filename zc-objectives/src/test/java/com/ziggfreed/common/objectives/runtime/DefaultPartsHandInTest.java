package com.ziggfreed.common.objectives.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.quest.InMemoryQuestProgressStore;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.QuestInventoryConsumer;
import com.ziggfreed.common.quest.QuestPossessionProbe;
import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.subject.Subject;

/**
 * The Hand in button, which is only ever as alive as the two inventory seams behind it.
 *
 * <p>An engine built without them refuses every item delivery - {@code attemptTurnIn} takes nothing
 * and credits nothing - while the book still shows the button, because the step it offers is
 * genuinely outstanding. So the failure looks like a broken menu rather than like missing wiring,
 * which is why the "neither seam wired" case is pinned here beside the working one.
 *
 * <p>The real seams read a live inventory, so an in-test bag stands in for one; what the bag proves
 * is the CONTRACT the runtime's own pair has to honour, partial delivery included. That the runtime
 * refuses when there is no player behind the subject at all is checked against the real methods.
 */
class DefaultPartsHandInTest {

    private static final String QUEST_ID = "q_deliver";
    private static final String STEP_ID = "deliver";
    private static final String ITEM = "Coin_Gold";

    /** A player's pockets, as far as a hand-in is concerned. */
    private static final class Bag {

        private final Map<String, Integer> held = new HashMap<>();

        void put(@Nonnull String itemId, int count) {
            held.merge(itemId, count, Integer::sum);
        }

        int count(@Nonnull String itemId) {
            return held.getOrDefault(itemId, 0);
        }

        boolean holds(@Nonnull Subject subject, @Nonnull String itemId, int count) {
            return count(itemId) >= count;
        }

        int take(@Nonnull Subject subject, @Nonnull String itemId, int max) {
            int taken = Math.min(Math.max(0, max), count(itemId));
            held.put(itemId, count(itemId) - taken);
            return taken;
        }
    }

    @Nonnull
    private static Quest deliveryQuest() {
        return Quest.builder(QUEST_ID)
                .objective(ObjectiveDef.builder(STEP_ID, "TURN_IN").target(ITEM).amount(3).build())
                .reward(RewardSpec.of("NOTE", "text", "parked"))
                .build();
    }

    @Nonnull
    private static Subject subject() {
        return Subject.of(UUID.randomUUID(), "tester");
    }

    /** The engine as it is built when the two seams are left at their refusing defaults. */
    @Nonnull
    private static QuestEngine engineWithoutSeams() {
        return QuestEngine.builder()
                .store(new InMemoryQuestProgressStore())
                .nativeEvents(false)
                .warn(message -> { })
                .build();
    }

    /** The engine as the runtime builds it: both halves of a hand-in wired to the same source. */
    @Nonnull
    private static QuestEngine engineOver(@Nonnull Bag bag) {
        QuestPossessionProbe holds = bag::holds;
        QuestInventoryConsumer takes = bag::take;
        return QuestEngine.builder()
                .store(new InMemoryQuestProgressStore())
                .possessionProbe(holds)
                .inventoryConsumer(takes)
                .nativeEvents(false)
                .warn(message -> { })
                .build();
    }

    @Test
    void withoutTheSeamsTheBooksOwnSequenceHandsInNothing() {
        QuestEngine engine = engineWithoutSeams();
        Quest quest = deliveryQuest();
        engine.setQuests(List.of(quest));
        Subject subject = subject();
        assertTrue(engine.accept(subject, quest));

        // Exactly what the book presses: the "somewhere unlocked" step, then the attempt.
        ObjectiveDef step = engine.firstActiveTurnIn(subject, quest, null);

        assertNotNull(step, "the button is offered, which is what makes the silence look like a bug");
        assertEquals(0, engine.attemptTurnIn(subject, quest, step.id()),
                "an engine with no inventory access takes nothing, however much the player carries");
    }

    @Test
    void withBothSeamsTheSameSequenceLands() {
        Bag bag = new Bag();
        bag.put(ITEM, 3);
        QuestEngine engine = engineOver(bag);
        Quest quest = deliveryQuest();
        engine.setQuests(List.of(quest));
        Subject subject = subject();
        assertTrue(engine.accept(subject, quest));

        ObjectiveDef step = engine.firstActiveTurnIn(subject, quest, null);
        assertNotNull(step);

        assertEquals(3, engine.attemptTurnIn(subject, quest, step.id()));
        assertEquals(0, bag.count(ITEM), "what was credited is what left the player's pockets");
        assertEquals(QuestStatus.COMPLETED_UNCLAIMED, engine.status(subject, quest));
    }

    @Test
    void aPartialDeliveryIsCreditedAndTheRestStaysOwed() {
        Bag bag = new Bag();
        bag.put(ITEM, 1);
        QuestEngine engine = engineOver(bag);
        Quest quest = deliveryQuest();
        engine.setQuests(List.of(quest));
        Subject subject = subject();
        assertTrue(engine.accept(subject, quest));

        assertEquals(1, engine.attemptTurnIn(subject, quest, STEP_ID));
        assertEquals(QuestStatus.ACTIVE, engine.status(subject, quest));

        bag.put(ITEM, 2);

        assertEquals(2, engine.attemptTurnIn(subject, quest, STEP_ID),
                "a second visit finishes what the first one could only start");
        assertEquals(QuestStatus.COMPLETED_UNCLAIMED, engine.status(subject, quest));
    }

    @Test
    void theRuntimesOwnSeamsRefuseASubjectWithNoPlayerBehindIt() {
        Subject handleLess = subject();

        assertFalse(ProgressionDefaults.holdsItems(handleLess, ITEM, 1),
                "fail closed: nothing may be credited for a subject nothing can be taken from");
        assertEquals(0, ProgressionDefaults.takeItems(handleLess, ITEM, 3));
    }
}
