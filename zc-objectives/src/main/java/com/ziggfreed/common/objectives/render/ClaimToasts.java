package com.ziggfreed.common.objectives.render;

import java.util.List;
import java.util.function.IntFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.loot.reward.RewardChips;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.progress.ClaimAttempt;
import com.ziggfreed.common.ui.toast.RewardToastLines;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastSpec;

/**
 * The ONE claim-toast reading for every surface a payout is collected on and no engine notice
 * announces (an achievement's Claim, a milestone's Claim, a conversation's or a used item's payout):
 * a gold headline plus one row per reward just handed over, read through the shared chip bridge so
 * the toast and the panel that previewed the claim can never disagree, capped with the caller's
 * overflow line. A quest's completion and collect are the engine's notices alone
 * ({@code Quest_Completed}, {@code Quest_Parked}, {@code Quest_Claimed}), which list their own rows,
 * so no surface composes a quest's here; every surface answers a refused Collect through
 * {@link #refusedToast}.
 *
 * <p>A player who presses Collect is told WHAT they collected. A bare headline leaves the payout to
 * be guessed at, which is why a collecting surface composes its toast here rather than sending a
 * line of its own.
 *
 * <p>Pure and page-free on purpose: what a claim toast says is decided (and testable) with no page
 * or server anywhere; a page only shows what this composes.
 */
public final class ClaimToasts {

    private ClaimToasts() {
    }

    /**
     * The in-page rank of a claim toast built on a page, the same rank the library's completion and
     * claim moments ship with, so a step's progress notice fired in the same moment never replaces it
     * ({@link ToastSpec#withPriority}).
     */
    public static final int CLAIM_PRIORITY = ToastSpec.PRIORITY_CLAIM;

    /**
     * The gold claim toast for {@code rewards}. An empty payout degrades to the plain headline;
     * {@code source} is the consumer's own chip reading, exactly as on every chip surface. Ranked
     * {@link #CLAIM_PRIORITY}.
     */
    @Nonnull
    public static ToastSpec rewardToast(@Nonnull Message headline, @Nonnull List<RewardSpec> rewards,
            @Nullable RewardChips.Source source, @Nullable IntFunction<Message> overflow) {
        return ToastSpec.of(ToastKind.REWARD, headline)
                .withLines(RewardToastLines.lines(rewards, source, overflow))
                .withPriority(CLAIM_PRIORITY);
    }

    /**
     * The ONE answer to a Collect press that paid nothing, on every surface a collect is pressed on, read off the
     * engine's own refusal ({@code attemptClaim}), never a second look at the bag: rewards that will not fit say
     * "make room" ({@link #noRoomToast}), a quest bound to another place says where it is collected, and anything
     * else says there is nothing to collect ({@link #nothingToCollectToast}). A missing attempt reads as nothing
     * waiting.
     */
    @Nonnull
    public static ToastSpec refusedToast(@Nullable ClaimAttempt attempt) {
        ClaimAttempt.Refusal why = ClaimAttempt.refusalOf(attempt);
        if (why == ClaimAttempt.Refusal.NO_ROOM) {
            return noRoomToast();
        }
        if (why == ClaimAttempt.Refusal.ELSEWHERE) {
            return ToastSpec.of(ToastKind.ERROR, line("book.quests.claim_at_site"));
        }
        return nothingToCollectToast();
    }

    /** A collect refused because its rewards will not fit: "Make room to collect your rewards." */
    @Nonnull
    public static ToastSpec noRoomToast() {
        return ToastSpec.of(ToastKind.ERROR, line("book.achievements.inventory_full"));
    }

    /** A collect pressed with nothing waiting: "Nothing to collect right now." */
    @Nonnull
    public static ToastSpec nothingToCollectToast() {
        return ToastSpec.of(ToastKind.WARNING, line("book.toast.claim_failed"));
    }

    /** A line of the library's progression lang file, the one the book and the NPC quest page speak from. */
    @Nonnull
    private static Message line(@Nonnull String key) {
        return Msg.tr("ziggfreedcommon.", "progression." + key);
    }
}
