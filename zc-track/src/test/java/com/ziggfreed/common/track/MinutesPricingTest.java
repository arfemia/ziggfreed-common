package com.ziggfreed.common.track;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.instance.reward.DeferredRewards;
import com.ziggfreed.common.instance.reward.InstanceReward;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.track.lamp.TrackLampRewardKind;
import com.ziggfreed.common.track.price.TrackPriceAsset;
import com.ziggfreed.common.track.price.TrackPricing;

/**
 * A reward priced in minutes (R618): the price row's minutes and levels with a reward's own over them, and the
 * reward as its Claim keeps it once priced (an exact amount, the price leaves gone), so what was earned is what
 * pays, past every boost.
 */
class MinutesPricingTest {

    @Test
    void aRewardsOwnLeavesWinOverItsPriceRow() {
        TrackPriceAsset row = TrackPriceAsset.of("Dungeon_Clear", 20.0, null, 30);
        TrackPricing.Priced asRow = TrackPricing.over(row, null, null, null);
        assertEquals(20.0, asRow.minutes());
        assertEquals(0, asRow.level());
        assertEquals(30, asRow.maxLevel());
        TrackPricing.Priced deeper = TrackPricing.over(row, 40.0, null, 85);
        assertEquals(40.0, deeper.minutes(), "the reward's own minutes");
        assertEquals(85, deeper.maxLevel(), "the reward's own cap");
        TrackPricing.Priced unknownRow = TrackPricing.over(null, null, 50, null);
        assertFalse(unknownRow.pays(), "a row no layer holds and no minutes of its own pays nothing");
        assertEquals(50, unknownRow.level());
    }

    @Test
    void aRewardIsPricedOnlyWhenItNamesAPriceOrMinutes() {
        assertTrue(TrackPricing.isPriced(RewardSpec.of("Track", Map.of("Track", "Mining", "Price", "Quest"))));
        assertTrue(TrackPricing.isPriced(RewardSpec.of("Track", Map.of("Track", "Mining", "Minutes", "15"))));
        assertFalse(TrackPricing.isPriced(RewardSpec.of("Track", Map.of("Track", "Mining", "Amount", "500"))));
        assertNull(TrackPricing.of(RewardSpec.of("Track", Map.of("Track", "Mining", "Amount", "500"))));
        TrackPricing.Priced inline = TrackPricing.of(RewardSpec.of("Track",
                Map.of("Track", "Mining", "Minutes", "12.5", "Level", "40", "MaxLevel", "x")));
        assertEquals(12.5, inline.minutes());
        assertEquals(40, inline.level());
        assertEquals(0, inline.maxLevel(), "a leaf that does not read is unwritten");
    }

    @Test
    void aPricedRewardIsKeptAsAnExactAmountWithItsPriceLeavesGone() {
        RewardSpec earned = TrackRewardKind.priced("Mining", "Quest", 15.0, 40, null);
        RewardSpec kept = TrackRewardKind.stamped(earned, 7333L);
        assertEquals(7333L, TrackRewardKind.amountOf(kept));
        assertTrue(TrackRewardKind.isExact(kept), "a priced amount pays past every boost");
        assertFalse(TrackPricing.isPriced(kept), "once priced it is never priced again");
        assertNull(kept.param("Level"));
        assertEquals("Mining", TrackRewardKind.trackOf(kept));
        assertFalse(TrackRewardKind.isExact(TrackRewardKind.spec("Mining", 500)), "a plain amount takes the boosts");
    }

    /**
     * A lamp kept in a Claim is worth its minutes, so a payout's diminishing returns hand a lamp of the share the
     * Claim shows (R1148: half the minutes on a second clear), never the whole lamp on a coin flip: its deferred row
     * reads the minutes as its amount, and its line filled with any share reads as the lamp of that share replays.
     */
    @Test
    void aLampKeptInAClaimScalesByItsMinutes() {
        RewardKindRegistry kinds = new RewardKindRegistry();
        TrackLampRewardKind.registerInto(kinds);
        Subject claimer = Subject.of(UUID.randomUUID(), "{player}");
        RewardSpec lamp = RewardSpec.of(TrackLampRewardKind.KIND,
                Map.of("Price", "Dungeon_Clear", "Minutes", "20", "MaxLevel", "30", "Category", "Combat,Magic"));
        List<InstanceReward> rows = DeferredRewards.fromSpecs(List.of(lamp), kinds, claimer, "test", null);
        assertEquals(1, rows.size());
        InstanceReward row = rows.get(0);
        assertEquals(20, row.quantity(), "the lamp's minutes are its amount");
        assertTrue(row.scalesWithQuantity(), "a share of the Claim is a share of the lamp");
        String half = row.id().replace(InstanceReward.AMOUNT_PLACEHOLDER, "10");
        assertEquals(kinds.handler(TrackLampRewardKind.KIND).retryCommand(lamp.with("Minutes", "10"), claimer, "test"),
                half, "the half share replays as a lamp of half the minutes");
    }
}
