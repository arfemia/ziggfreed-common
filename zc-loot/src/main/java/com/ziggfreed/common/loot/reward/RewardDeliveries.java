package com.ziggfreed.common.loot.reward;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.event.NativeEventSeam;
import com.ziggfreed.common.subject.Subject;

/**
 * What a reward payout handed over, announced once. {@link RewardGrants#grantAll} is the path quest,
 * achievement, milestone, shop, encounter and loot-table {@code Rewards} payouts take, and its receipt
 * is what actually reached the player, so this is where "a player received an item" is decided for
 * all of them at once. Two payouts never announce: instance spoils, which pay through
 * {@code InstanceRewardGranter} instead, and a replay from the retry queue of a reward that could not
 * be handed over at the time.
 *
 * <p>Payouts nest: a reward that rolls a table pays the table's own {@code Rewards} through a payout
 * of its own, and reports what that payout handed over on its receipt. So only the OUTERMOST payout
 * on a thread announces, with the whole receipt, and an item is never announced twice.
 */
public final class RewardDeliveries {

    private static final NativeEventSeam SEAM = new NativeEventSeam("[grant]");

    /** How deep the payout running on this thread is nested. */
    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private RewardDeliveries() {
    }

    /**
     * Route every announcement through {@code publisher} instead of the engine bus; null restores the
     * bus. For a host outside a Hytale server and for a test observing what a payout announces.
     */
    public static void publishTo(@Nullable NativeEventSeam.Publisher publisher) {
        SEAM.publishTo(publisher);
    }

    /**
     * The items a receipt says were handed over: every row naming an item with a positive count,
     * read the way the item kinds pay them ({@code LootRewardKinds.itemIdOf} and {@code countOf}),
     * merged per item in first-landed order.
     */
    @Nonnull
    public static List<LootReceivedEvent.Received> receivedItems(@Nonnull List<RewardSpec> receipt) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (RewardSpec spec : receipt) {
            if (spec == null) {
                continue;
            }
            String itemId = LootRewardKinds.itemIdOf(spec);
            int count = LootRewardKinds.countOf(spec);
            if (itemId == null || count <= 0) {
                continue;
            }
            counts.merge(itemId, (long) count, Long::sum);
        }
        List<LootReceivedEvent.Received> out = new ArrayList<>(counts.size());
        counts.forEach((itemId, count) -> out.add(new LootReceivedEvent.Received(itemId, count)));
        return out;
    }

    /** A payout starts on this thread. */
    static void enter() {
        DEPTH.get()[0]++;
    }

    /** A payout ends on this thread; true when it was the outermost one. */
    static boolean leave() {
        int[] depth = DEPTH.get();
        depth[0] = Math.max(0, depth[0] - 1);
        return depth[0] == 0;
    }

    /** Announce what the outermost payout handed over; nothing when it handed over no item. */
    static void announce(@Nonnull Subject subject, @Nonnull String sourceId, @Nonnull List<RewardSpec> receipt) {
        List<LootReceivedEvent.Received> items = receivedItems(receipt);
        if (items.isEmpty()) {
            return;
        }
        SEAM.fire("LootReceived", LootReceivedEvent.class,
                () -> new LootReceivedEvent(subject.id(), sourceId, items));
    }
}
