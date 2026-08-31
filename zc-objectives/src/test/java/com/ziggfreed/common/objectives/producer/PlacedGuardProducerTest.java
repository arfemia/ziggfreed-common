package com.ziggfreed.common.objectives.producer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.achievement.AchievementEngine;
import com.ziggfreed.common.achievement.InMemoryAchievementProgressStore;
import com.ziggfreed.common.progress.MatchMode;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.ObjectiveProgressState;
import com.ziggfreed.common.quest.InMemoryQuestProgressStore;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.world.placed.PlacedBlockLedger;

/**
 * The anti-exploit half of the pickup producer, driven all the way to a real engine: an item the
 * picker placed advances NOTHING, while an ordinary one advances the quest.
 *
 * <p>Each test runs the producer's own two steps in the producer's own order - ask the shared
 * {@link PlacedBlockLedger}, and dispatch only on a no - over the real
 * {@link ProgressDispatch#dispatch} and a real {@link QuestEngine}. The ECS half (turning a native
 * event into a store, a ref and a {@code PlayerRef}) is what lands behind in-game smoke, as with
 * every other producer.
 *
 * <p><b>The BLOCK half is not driven from here.</b> A placement is kept on its own chunk section
 * now, so asking the ledger about one needs a live world and a loaded chunk. What decides the
 * answer is pinned in {@code PlacedBlockSectionTest} (which bit a position maps to, that spending a
 * mark clears it, that marks survive a save and load); the producer's own branch - refuse when the
 * ledger says placed, dispatch otherwise - is the same shape as the pickup one below, and lands in
 * in-game smoke.
 *
 * <p>The guard living in the LIBRARY is what makes it trustworthy: a consumer's own XP path reads
 * the SAME ledger, so quest progress and XP can never disagree about whether a block was placed.
 */
class PlacedGuardProducerTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-00000000e001");
    private static final String OWNER = "test";

    private final PlacedBlockLedger ledger = PlacedBlockLedger.getInstance();

    private Subject player;
    private QuestEngine quests;
    private AchievementEngine achievements;

    @BeforeEach
    void setUp() {
        ledger.clear();
        ledger.setPolicy(OWNER, PlacedBlockLedger.Policy.DEFAULT);

        player = Subject.of(PLAYER, "tester");
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
        ledger.clear();
        ledger.setPolicy(OWNER, PlacedBlockLedger.Policy.DEFAULT);
    }

    @Nonnull
    private Quest quest(@Nonnull String questId, @Nonnull String objectiveId, @Nonnull String kind,
            @Nonnull String target) {
        Quest quest = Quest.builder(questId)
                .objective(ObjectiveDef.builder(objectiveId, kind)
                        .target(target).matchMode(MatchMode.EXACT).amount(3).build())
                .build();
        quests.setQuests(List.of(quest));
        assertTrue(quests.accept(player, quest));
        return quest;
    }

    /** Exactly what {@code ZigPickupProducer.handle} does, {@code momentKey} and all. */
    private void handlePickup(@Nonnull String itemId, long momentKey) {
        if (ledger.consumePlacedItem(PLAYER, itemId, momentKey)) {
            return;
        }
        ProgressDispatch.dispatch(quests, achievements, player, player,
                ZigPickupProducer.KIND, itemId, null, 1L, null);
    }

    private int progress(@Nonnull String questId, @Nonnull String objectiveId) {
        ObjectiveProgressState state = quests.progressOf(player, questId, objectiveId);
        return state == null ? 0 : state.current();
    }

    @Test
    void aPlacedThenPickedUpItemDispatchesNoPickupProgress() {
        quest("q_gather", "saplings", ZigPickupProducer.KIND, "Sapling");
        ledger.trackPlacedItem(PLAYER, "Sapling");

        handlePickup("Sapling", 1L);

        assertEquals(0, progress("q_gather", "saplings"),
                "putting a sapling down and picking it back up cannot advance a pickup objective");
    }

    @Test
    void anItemNobodyPlacedDispatchesPickupProgress() {
        quest("q_gather", "saplings", ZigPickupProducer.KIND, "Sapling");

        handlePickup("Sapling", 1L);

        assertEquals(1, progress("q_gather", "saplings"), "a genuine find counts");
    }

    /**
     * Two systems read one native pickup: this producer and the consumer's own XP system, in an
     * order nobody specifies. Both have to hear the same answer, or whichever ran second would pay
     * out on exactly the exploit the first one refused.
     *
     * <p>The record is a COUNT rather than a position and the moment therefore has to be named. Two
     * copies placed and two readers per pickup must still cost two pickups,
     * not one: a version without a moment key spends both copies on the first pickup and hands the
     * second one back.
     */
    @Test
    void twoReadersOfOnePickupStillCostOnePlacedCopy() {
        quest("q_gather", "saplings", ZigPickupProducer.KIND, "Sapling");
        ledger.trackPlacedItem(PLAYER, "Sapling");
        ledger.trackPlacedItem(PLAYER, "Sapling");

        assertTrue(ledger.consumePlacedItem(PLAYER, "Sapling", 77L), "a consumer's XP path first");
        handlePickup("Sapling", 77L);
        assertEquals(0, progress("q_gather", "saplings"), "the first pickup pays nothing");

        assertTrue(ledger.consumePlacedItem(PLAYER, "Sapling", 78L), "the second pickup, XP first");
        handlePickup("Sapling", 78L);
        assertEquals(0, progress("q_gather", "saplings"),
                "and the second pays nothing either, because two were put down");

        handlePickup("Sapling", 79L);
        assertEquals(1, progress("q_gather", "saplings"), "only the third is a genuine find");
    }
}
