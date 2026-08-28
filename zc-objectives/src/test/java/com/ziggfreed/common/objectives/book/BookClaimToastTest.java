package com.ziggfreed.common.objectives.book;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.loot.reward.RewardChip;
import com.ziggfreed.common.loot.reward.RewardChips;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastRenderer;
import com.ziggfreed.common.ui.toast.ToastSpec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The book's claim toasts carry their payout: one row per reward just handed over, through the ONE
 * shared chip bridge, capped with the overflow line. All three claim verbs (a quest's Claim, an
 * achievement's Claim, a milestone's Claim) build their toast through this same reading, so what a
 * claim toast says is pinned here once.
 */
class BookClaimToastTest {

    @Test
    void aClaimToastCarriesOneRowPerRewardAndReadsGold() {
        ToastSpec spec = ClaimToasts.rewardToast(Msg.raw("Rewards claimed!"),
                rewards(2), source(), dropped -> Msg.raw("+" + dropped + " more"));

        assertEquals(ToastKind.REWARD, spec.kind());
        assertEquals(2, spec.lines().size(), "one row per reward just claimed");
        assertFalse(spec.lines().isEmpty(), "a bare headline was the reported bug");
    }

    @Test
    void anEmptyPayoutDegradesToThePlainHeadline() {
        ToastSpec spec = ClaimToasts.rewardToast(Msg.raw("Rewards claimed!"),
                List.of(), source(), dropped -> Msg.raw("+" + dropped + " more"));
        assertTrue(spec.lines().isEmpty());
        assertEquals(ToastKind.REWARD, spec.kind());
    }

    @Test
    void aLongPayoutSpendsTheLastRowOnTheOverflowLine() {
        int total = ToastRenderer.MAX_LINES + 4;
        ToastSpec spec = ClaimToasts.rewardToast(Msg.raw("Rewards claimed!"),
                rewards(total), source(), dropped -> Msg.raw("+" + dropped + " more"));

        assertEquals(ToastRenderer.MAX_LINES, spec.lines().size());
        assertEquals("+" + (total - (ToastRenderer.MAX_LINES - 1)) + " more",
                spec.lines().get(ToastRenderer.MAX_LINES - 1).text().getFormattedMessage().rawText);
    }

    /** The consumer chip source, the seam every book surface reads rewards through. */
    private static RewardChips.Source source() {
        return spec -> RewardChip.text(Msg.raw("reward " + spec.paramOr("i", "?")));
    }

    private static List<RewardSpec> rewards(int count) {
        List<RewardSpec> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(RewardSpec.of("test_kind", Map.of("i", Integer.toString(i))));
        }
        return out;
    }
}
