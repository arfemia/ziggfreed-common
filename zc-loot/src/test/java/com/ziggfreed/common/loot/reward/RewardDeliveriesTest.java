package com.ziggfreed.common.loot.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.event.IEvent;
import com.ziggfreed.common.event.NativeEventSeam;
import com.ziggfreed.common.subject.Subject;

/**
 * What a reward payout announces: every item its receipt says reached the player, once, named by
 * what paid the whole payout, and nothing for a payout that handed over no item. A table's own
 * {@code Rewards} pay through a payout nested inside the reward rolling it, and must not be announced
 * twice.
 */
class RewardDeliveriesTest {

    private final List<LootReceivedEvent> announced = new ArrayList<>();
    private Subject player;
    private RewardKindRegistry kinds;

    @BeforeEach
    void setUp() {
        player = Subject.of(UUID.randomUUID(), "tester");
        kinds = new RewardKindRegistry();
        kinds.register("FIXTURE_ITEM", (spec, subject) -> { });
        kinds.register("FIXTURE_NOTE", (spec, subject) -> { });
        kinds.register("FIXTURE_BROKEN", (spec, subject) -> {
            throw new IllegalStateException("no room");
        });
        kinds.register("FIXTURE_TABLE", new RewardHandler() {
            @Override
            public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject) {
                throw new UnsupportedOperationException("granted through the receipt form only");
            }

            @Override
            public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject, @Nonnull String sourceId,
                    @Nonnull Consumer<RewardSpec> receipt) {
                RewardGrants.GrantOutcome inner = RewardGrants.grantAll(List.of(item("Fixture_Gem", 2)),
                        subject, "reward:fixture_table", kinds, null, warning -> { });
                inner.receipt().forEach(receipt);
            }
        });
        RewardDeliveries.publishTo(new NativeEventSeam.Publisher() {
            @Override
            public <E extends IEvent<Void>> void publish(@Nonnull Class<E> type, @Nonnull Supplier<E> build) {
                announced.add((LootReceivedEvent) build.get());
            }
        });
    }

    @AfterEach
    void tearDown() {
        RewardDeliveries.publishTo(null);
    }

    @Nonnull
    private static RewardSpec item(@Nonnull String id, int count) {
        return RewardSpec.of("FIXTURE_ITEM", Map.of("Item", id, "Count", Integer.toString(count)));
    }

    private void grant(@Nonnull List<RewardSpec> rewards) {
        RewardGrants.grantAll(rewards, player, "quest:fixture_quest", kinds, null, warning -> { });
    }

    @Test
    void aPayoutAnnouncesEveryItemItHandedOverOnce() {
        grant(List.of(item("Fixture_Lantern", 1), item("Fixture_Gem", 3), item("Fixture_Gem", 2)));

        assertEquals(1, announced.size(), "one payout, one announcement");
        LootReceivedEvent event = announced.get(0);
        assertEquals(player.id(), event.playerId());
        assertEquals("quest:fixture_quest", event.sourceId());
        assertEquals(List.of(new LootReceivedEvent.Received("Fixture_Lantern", 1),
                new LootReceivedEvent.Received("Fixture_Gem", 5)), event.items(),
                "one row per item, counts merged, in the order they first landed");
    }

    @Test
    void aNestedPayoutIsAnnouncedOnceByTheOuterOne() {
        grant(List.of(RewardSpec.of("FIXTURE_TABLE"), item("Fixture_Lantern", 1)));

        assertEquals(1, announced.size(), "the table's own payout announces nothing of its own");
        assertEquals("quest:fixture_quest", announced.get(0).sourceId(), "named by what paid the whole payout");
        assertEquals(List.of(new LootReceivedEvent.Received("Fixture_Gem", 2),
                new LootReceivedEvent.Received("Fixture_Lantern", 1)), announced.get(0).items());
    }

    @Test
    void aPayoutThatHandedOverNoItemAnnouncesNothing() {
        grant(List.of(RewardSpec.of("FIXTURE_NOTE", "Text", "well done")));

        assertTrue(announced.isEmpty());
    }

    @Test
    void aRewardThatWentNowhereWasNotReceived() {
        grant(List.of(RewardSpec.of("FIXTURE_BROKEN", Map.of("Item", "Fixture_Lantern", "Count", "1"))));

        assertTrue(announced.isEmpty(), "a failed or queued reward is not on the receipt");
    }

    @Test
    void aRowNamingNoItemOrNoCountIsSkipped() {
        assertEquals(List.of(new LootReceivedEvent.Received("Fixture_Gem", 1)),
                RewardDeliveries.receivedItems(List.of(
                        RewardSpec.of("FIXTURE_ITEM", Map.of("Item", "Fixture_Gem")),
                        RewardSpec.of("FIXTURE_ITEM", Map.of("Item", " ", "Count", "4")),
                        RewardSpec.of("FIXTURE_ITEM", Map.of("Item", "Fixture_Rock", "Count", "0")))),
                "an unstated count is one; a blank item or a count of none is nothing received");
    }

    /**
     * The item kinds pay a reward authored with {@code Id} exactly as one authored with {@code Item},
     * and report the spec itself on the receipt, so the announcement must read that row the way the
     * handler paid it. The two kinds are stood in by handlers that pay nothing, since what is under
     * test is how the receipt row is read, and the real kinds need a player.
     */
    @Test
    void anItemRewardAuthoredWithIdIsAnnouncedAsTheItemItPaid() {
        kinds.register(LootRewardKinds.KIND_ITEM, (spec, subject) -> { });
        kinds.register(LootRewardKinds.KIND_STAMPED_ITEM, (spec, subject) -> { });

        grant(List.of(
                RewardSpec.of(LootRewardKinds.KIND_ITEM, Map.of("Id", "Fixture_Lantern", "Count", "2")),
                RewardSpec.of(LootRewardKinds.KIND_STAMPED_ITEM, Map.of("Id", "Fixture_Gem", "Quantity", "3"))));

        assertEquals(1, announced.size(), "both rewards were paid, so the payout announces them");
        assertEquals(List.of(new LootReceivedEvent.Received("Fixture_Lantern", 2),
                new LootReceivedEvent.Received("Fixture_Gem", 3)), announced.get(0).items(),
                "Id names the item, and Quantity the count, as each handler reads them");
    }

    @Test
    void anIdOnAnyOtherKindNamesNoItem() {
        assertEquals(List.of(), RewardDeliveries.receivedItems(List.of(
                        RewardSpec.of("FIXTURE_EFFECT", Map.of("Id", "Fixture_Haste")))),
                "only the item kinds read Id as an item; another kind names its own thing by it");
    }

    @Test
    void eachPayoutAfterAnotherIsAnnouncedOnItsOwn() {
        grant(List.of(item("Fixture_Lantern", 1)));
        grant(List.of(item("Fixture_Gem", 1)));

        assertEquals(2, announced.size(), "the nesting count returns to zero after every payout");
    }
}
