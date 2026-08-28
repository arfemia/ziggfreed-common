package com.ziggfreed.common.objectives.book;

import java.util.List;
import java.util.function.IntFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.loot.reward.RewardChips;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.ui.toast.RewardToastLines;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastSpec;

/**
 * The ONE claim-toast reading for the book's three claim verbs (a quest's Claim, an achievement's
 * Claim, a milestone's Claim): a gold headline plus one row per reward just handed over, read
 * through the shared chip bridge so the toast and the panel that previewed the claim can never
 * disagree, capped with the caller's overflow line.
 *
 * <p>Pure and page-free on purpose: what a claim toast says is decided (and testable) with no page
 * or server anywhere; {@code ObjectiveBookPage} only shows what this composes.
 */
final class ClaimToasts {

    private ClaimToasts() {
    }

    /**
     * The gold claim toast for {@code rewards}. An empty payout degrades to the plain headline;
     * {@code source} is the consumer's own chip reading, exactly as on every chip surface.
     */
    @Nonnull
    static ToastSpec rewardToast(@Nonnull Message headline, @Nonnull List<RewardSpec> rewards,
            @Nullable RewardChips.Source source, @Nullable IntFunction<Message> overflow) {
        return ToastSpec.of(ToastKind.REWARD, headline)
                .withLines(RewardToastLines.lines(rewards, source, overflow));
    }
}
