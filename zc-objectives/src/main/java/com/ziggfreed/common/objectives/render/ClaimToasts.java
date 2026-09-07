package com.ziggfreed.common.objectives.render;

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
 * The ONE claim-toast reading for every surface a payout is collected on - the book's three claim
 * verbs (a quest's Claim, an achievement's Claim, a milestone's Claim) and the same collection made
 * standing in front of a character: a gold headline plus one row per reward just handed over, read
 * through the shared chip bridge so the toast and the panel that previewed the claim can never
 * disagree, capped with the caller's overflow line.
 *
 * <p>A player who presses Collect is told WHAT they collected. A bare headline leaves the payout to
 * be guessed at, which is why every collecting surface composes its toast here rather than sending
 * a line of its own.
 *
 * <p>Pure and page-free on purpose: what a claim toast says is decided (and testable) with no page
 * or server anywhere; a page only shows what this composes.
 */
public final class ClaimToasts {

    private ClaimToasts() {
    }

    /**
     * The gold claim toast for {@code rewards}. An empty payout degrades to the plain headline;
     * {@code source} is the consumer's own chip reading, exactly as on every chip surface.
     */
    @Nonnull
    public static ToastSpec rewardToast(@Nonnull Message headline, @Nonnull List<RewardSpec> rewards,
            @Nullable RewardChips.Source source, @Nullable IntFunction<Message> overflow) {
        return ToastSpec.of(ToastKind.REWARD, headline)
                .withLines(RewardToastLines.lines(rewards, source, overflow));
    }
}
