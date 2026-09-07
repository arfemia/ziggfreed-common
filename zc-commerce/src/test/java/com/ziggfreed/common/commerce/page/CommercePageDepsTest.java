package com.ziggfreed.common.commerce.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.loot.reward.RewardChip;
import com.ziggfreed.common.loot.reward.RewardChips;
import com.ziggfreed.common.loot.reward.RewardGrants;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.ui.toast.RewardToastLines;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastSpec;

/**
 * The board's completion toast through the commerce deps: with no consumer fill the library's
 * line lists what the collect or hand-in actually handed over, a fill written for the plain form
 * ignores the rows and still owns the toast, a fill that paints rows is handed them, and a fill
 * that throws costs its own line only. Pinned with no page or server anywhere, because what the
 * board says after a payout is decided here.
 */
class CommercePageDepsTest {

    private static final List<RewardSpec> ROLLED = List.of(
            RewardSpec.of("Item", Map.of("Item", "Coin_Gold", "Count", "3")),
            RewardSpec.of("Item", Map.of("Item", "Gem_Ruby", "Count", "1")));

    /** A chip reading that names every reward, so the rows are countable with no server. */
    @Nonnull
    private static RewardChips.Source naming() {
        return spec -> RewardChip.text(Msg.raw(spec.paramOr("Item", "?")));
    }

    @Test
    void theDefaultsAnswerNeutrally() {
        assertNull(CommercePageDeps.DEFAULTS.completionToast().forCompleted("bounty"),
                "no consumer toast, so the board floats its own line");
        assertNull(CommercePageDeps.DEFAULTS.completionToast().forCompleted("bounty", ROLLED),
                "and the rows form hands off to the same answer");
        assertSame(CommercePageDeps.NO_COMPLETION_TOAST,
                CommercePageDeps.builder().completionToast(null).build().completionToast());
    }

    @Test
    void withNoConsumerToastTheLibraryLineListsWhatWasPaid() {
        CommercePageDeps deps = CommercePageDeps.builder().rewardChips(naming()).build();

        ToastSpec spec = deps.resolveCompletionToast("bounty", ROLLED, Msg.raw("Contract complete"),
                dropped -> Msg.raw("+" + dropped + " more"));

        assertEquals(ToastKind.REWARD, spec.kind());
        assertEquals(2, spec.lines().size(), "one row per thing actually handed over");
        assertTrue(deps.resolveCompletionToast("bounty", List.of(), Msg.raw("Contract complete"), null)
                .lines().isEmpty(), "an empty roll lists nothing under the headline");
    }

    /**
     * The rule the board's Hand in floats its toast by: a contract that PAID OUT here is the gold
     * line listing the payout's receipt, and one that PARKED to be collected is the plain "handed
     * in" line with no row at all. Gold is the payout colour and nothing has been paid yet; the
     * authored reward is never listed either way, because a promise under a gold headline shows a
     * player items they were not given.
     */
    @Test
    void aParkedHandInIsThePlainLineAndAPaidOneIsGoldWithItsReceipt() {
        CommercePageDeps deps = CommercePageDeps.builder().rewardChips(naming()).build();
        RewardGrants.GrantOutcome paid = new RewardGrants.GrantOutcome(1, 0, 0, ROLLED);

        ToastSpec parked = deps.handInToast("bounty", null, Msg.raw("Contract complete"),
                Msg.raw("Handed in."), null);
        assertEquals(ToastKind.SUCCESS, parked.kind(), "not the payout colour: nothing was paid");
        assertEquals("Handed in.", parked.message().getRawText());
        assertTrue(parked.lines().isEmpty(),
                "nothing was paid, so the authored reward is not listed as handed over");

        ToastSpec settled = deps.handInToast("bounty", paid, Msg.raw("Contract complete"),
                Msg.raw("Handed in."), null);
        assertEquals(ToastKind.REWARD, settled.kind());
        assertEquals("Contract complete", settled.message().getRawText());
        assertEquals(2, settled.lines().size(), "the receipt, never the contract's own list");
    }

    /** A fill written for the one-argument form ignores the rows and still owns the toast. */
    @Test
    void aConsumerToastThatIgnoresTheRowsStillWorks() {
        ToastSpec mine = ToastSpec.of(ToastKind.SUCCESS, Msg.raw("Contract done, my way"));
        CommercePageDeps deps = CommercePageDeps.builder()
                .rewardChips(naming())
                .completionToast(bountyId -> mine)
                .build();

        assertSame(mine, deps.resolveCompletionToast("bounty", ROLLED,
                Msg.raw("Contract complete"), null));
    }

    /** A fill that paints rows is handed the receipt, never left to read the contract's own list. */
    @Test
    void aConsumerToastThatPaintsRowsIsHandedTheReceipt() {
        CommercePageDeps deps = CommercePageDeps.builder()
                .rewardChips(naming())
                .completionToast(new CommercePageDeps.CompletionToast() {
                    @Override
                    @Nullable
                    public ToastSpec forCompleted(@Nonnull String bountyId) {
                        return ToastSpec.of(ToastKind.SUCCESS, Msg.raw("rows unknown"));
                    }

                    @Override
                    @Nullable
                    public ToastSpec forCompleted(@Nonnull String bountyId,
                            @Nonnull List<RewardSpec> rewards) {
                        return ToastSpec.of(ToastKind.REWARD, Msg.raw("mine"))
                                .withLines(RewardToastLines.lines(rewards, naming(), null));
                    }
                })
                .build();

        ToastSpec spec = deps.resolveCompletionToast("bounty", ROLLED,
                Msg.raw("Contract complete"), null);

        assertEquals(ToastKind.REWARD, spec.kind());
        assertEquals(2, spec.lines().size(), "the rows the board decided on, painted by the fill");
    }

    @Test
    void aConsumerToastThatThrowsCostsItsOwnLineOnly() {
        CommercePageDeps deps = CommercePageDeps.builder()
                .rewardChips(naming())
                .completionToast(bountyId -> {
                    throw new IllegalStateException("boom");
                })
                .build();

        ToastSpec spec = deps.resolveCompletionToast("bounty", ROLLED,
                Msg.raw("Contract complete"), null);

        assertEquals(ToastKind.REWARD, spec.kind());
        assertEquals(2, spec.lines().size(), "the library line, with the receipt under it");
    }
}
