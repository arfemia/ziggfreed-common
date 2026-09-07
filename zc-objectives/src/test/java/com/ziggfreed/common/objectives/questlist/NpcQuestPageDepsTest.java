package com.ziggfreed.common.objectives.questlist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.loot.reward.RewardChip;
import com.ziggfreed.common.loot.reward.RewardGrants;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.ui.toast.RewardToastLines;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastSpec;

/**
 * The page's deps: that an unfilled seam leaves a WORKING page, and that a filled one failing costs
 * its own contribution rather than the screen.
 *
 * <p>Both halves matter for the same reason. The defaults are what a bare server runs on, so a
 * default that answered null or empty would be a page nobody could open without a consumer; and every
 * seam here is somebody else's code running inside a render pass, where a throw would take the whole
 * panel with it.
 */
class NpcQuestPageDepsTest {

    @AfterEach
    void clearRegisteredDeps() {
        NpcQuestPages.deps(null);
    }

    // ==================== the defaults ====================

    @Test
    void aCharacterAnswersToItsOwnIdByDefault() {
        assertEquals(Set.of("Guide"), NpcQuestPageDeps.DEFAULTS.answerSetOrOwn("Guide"));
    }

    @Test
    void nobodyInFrontOfThePlayerAnswersToNothing() {
        // Not "everything": a page opened with no character must list nothing HERE rather than
        // quietly listing every quest on the server as though this nobody handed them all out.
        assertTrue(NpcQuestPageDeps.DEFAULTS.answerSetOrOwn(null).isEmpty());
        assertTrue(NpcQuestPageDeps.DEFAULTS.answerSetOrOwn("   ").isEmpty());
    }

    @Test
    void noNameSourceMeansNoNameRatherThanAGuess() {
        // A guessed name renders as text somebody chose, so a wrong one is worse than none: the page
        // falls back to the character's own id, which is at least true.
        assertNull(NpcQuestPageDeps.DEFAULTS.nameOrNull("Guide"));
    }

    @Test
    void theDefaultSeamsAllAnswerNeutrally() {
        assertNull(NpcQuestPageDeps.DEFAULTS.rewardChips().chipFor(RewardSpec.of("Anything")),
                "no consumer opinion, so every chip takes the generic reading");
        assertFalse(NpcQuestPageDeps.DEFAULTS.completion().handOff(
                "quest", "Guide", null, null, null),
                "with no quest runtime installed nothing is authored, so the page keeps the screen");
        assertNull(NpcQuestPageDeps.DEFAULTS.completionToast().forCompleted(null),
                "no consumer toast, so the page floats its own line");
    }

    // ==================== the builder ====================

    @Test
    void aFilledSeamIsTheOneThatIsAsked() {
        NpcQuestPageDeps deps = NpcQuestPageDeps.builder()
                .answerSets(npcId -> Set.of(npcId, "Alias"))
                .build();
        assertEquals(Set.of("Guide", "Alias"), deps.answerSetOrOwn("Guide"));
    }

    @Test
    void clearingASeamGoesBackToTheLibraryDefaultRatherThanToNull() {
        NpcQuestPageDeps deps = NpcQuestPageDeps.builder()
                .answerSets(null).npcNames(null).theme(null)
                .rewardChips(null).completion(null).completionToast(null)
                .build();
        assertSame(NpcQuestPageDeps.ASSET_ANSWER_SETS, deps.answerSets());
        assertSame(NpcQuestPageDeps.ASSET_NAMES, deps.npcNames());
        assertSame(NpcQuestPageDeps.PLAIN_THEME, deps.theme());
        assertSame(NpcQuestPageDeps.GENERIC_CHIPS, deps.rewardChips());
        assertSame(NpcQuestPageDeps.ENGINE_HAND_OFF, deps.completion());
        assertSame(NpcQuestPageDeps.NO_COMPLETION_TOAST, deps.completionToast());
    }

    // ==================== a seam that misbehaves ====================

    @Test
    void anIdentityLayerThatThrowsCostsTheAliasesAndNothingElse() {
        NpcQuestPageDeps deps = NpcQuestPageDeps.builder()
                .answerSets(npcId -> {
                    throw new IllegalStateException("boom");
                })
                .npcNames(npcId -> {
                    throw new IllegalStateException("boom");
                })
                .build();
        assertEquals(Set.of("Guide"), deps.answerSetOrOwn("Guide"));
        assertNull(deps.nameOrNull("Guide"));
    }

    @Test
    void anAnswerSetOfNothingStillLeavesTheCharacterAnsweringToItself() {
        // An identity layer that knows nothing about a character must not erase the character.
        NpcQuestPageDeps deps = NpcQuestPageDeps.builder().answerSets(npcId -> Set.of()).build();
        assertEquals(Set.of("Guide"), deps.answerSetOrOwn("Guide"));
    }

    // ==================== the hand-in's completion toast ====================

    private static final List<RewardSpec> ROLLED = List.of(
            RewardSpec.of("Item", Map.of("Item", "Coin_Gold", "Count", "3")),
            RewardSpec.of("Item", Map.of("Item", "Gem_Ruby", "Count", "1")));

    /** A chip reading that names every reward, so the rows are countable with no server. */
    @Nonnull
    private static NpcQuestPageDeps.RewardChipSource naming() {
        return spec -> RewardChip.text(Msg.raw(spec.paramOr("Item", "?")));
    }

    @Test
    void withNoConsumerToastTheLibraryLineListsWhatWasPaid() {
        NpcQuestPageDeps deps = NpcQuestPageDeps.builder().rewardChips(naming()).build();
        Quest quest = Quest.builder("q_done").build();

        ToastSpec spec = deps.resolveCompletionToast(quest, ROLLED, Msg.raw("Quest complete"),
                dropped -> Msg.raw("+" + dropped + " more"));

        assertEquals(ToastKind.REWARD, spec.kind());
        assertEquals(2, spec.lines().size(), "one row per thing actually handed over");
        assertTrue(deps.resolveCompletionToast(quest, List.of(), Msg.raw("Quest complete"), null)
                .lines().isEmpty(), "an empty roll lists nothing under the headline");
    }

    /**
     * The rule the character screen's hand-in floats its toast by: a quest that PAID OUT here is
     * the gold reward line listing the payout's receipt, and one that PARKED (a full bag, or it
     * collects somewhere else) is the plain "handed in" line with no row at all. Gold is the payout
     * colour, and a "complete" headline over nothing reads as a payout that paid nothing when in
     * truth nothing has been paid yet; the authored reward is never listed either way, because a
     * promise under a gold headline shows a player items they were not given.
     */
    @Test
    void aParkedHandInIsThePlainLineAndAPaidOneIsGoldWithItsReceipt() {
        NpcQuestPageDeps deps = NpcQuestPageDeps.builder().rewardChips(naming()).build();
        Quest quest = Quest.builder("q_parked")
                .reward(RewardSpec.of("Item", Map.of("Item", "Promised_Sword", "Count", "1")))
                .build();
        RewardGrants.GrantOutcome paid = new RewardGrants.GrantOutcome(1, 0, 0, ROLLED);

        ToastSpec parked = deps.handInToast(quest, null, Msg.raw("Quest complete"),
                Msg.raw("Handed in."), null);
        assertEquals(ToastKind.SUCCESS, parked.kind(), "not the payout colour: nothing was paid");
        assertEquals("Handed in.", parked.message().getRawText());
        assertTrue(parked.lines().isEmpty(),
                "nothing was paid, so the authored reward is not listed as handed over");

        ToastSpec settled = deps.handInToast(quest, paid, Msg.raw("Quest complete"),
                Msg.raw("Handed in."), null);
        assertEquals(ToastKind.REWARD, settled.kind());
        assertEquals("Quest complete", settled.message().getRawText());
        assertEquals(2, settled.lines().size(), "the receipt, never the quest's own list");
    }

    /** A consumer's own completion toast is asked only for a quest that paid out here. */
    @Test
    void aConsumerToastIsNotAskedForAParkedHandIn() {
        ToastSpec mine = ToastSpec.of(ToastKind.REWARD, Msg.raw("Paid, my way"));
        NpcQuestPageDeps deps = NpcQuestPageDeps.builder()
                .rewardChips(naming())
                .completionToast(quest -> mine)
                .build();
        Quest quest = Quest.builder("q").build();

        assertEquals("Handed in.", deps.handInToast(quest, null, Msg.raw("Quest complete"),
                Msg.raw("Handed in."), null).message().getRawText());
        assertSame(mine, deps.handInToast(quest, new RewardGrants.GrantOutcome(1, 0, 0, ROLLED),
                Msg.raw("Quest complete"), Msg.raw("Handed in."), null));
    }

    /** A fill written for the one-argument form ignores the rows and still owns the toast. */
    @Test
    void aConsumerToastThatIgnoresTheRowsStillWorks() {
        ToastSpec mine = ToastSpec.of(ToastKind.SUCCESS, Msg.raw("Handed in, my way"));
        NpcQuestPageDeps deps = NpcQuestPageDeps.builder()
                .rewardChips(naming())
                .completionToast(quest -> mine)
                .build();

        assertSame(mine, deps.resolveCompletionToast(Quest.builder("q").build(), ROLLED,
                Msg.raw("Quest complete"), null));
    }

    /** A fill that paints rows is handed the receipt, never left to read the quest's own list. */
    @Test
    void aConsumerToastThatPaintsRowsIsHandedTheReceipt() {
        NpcQuestPageDeps deps = NpcQuestPageDeps.builder()
                .rewardChips(naming())
                .completionToast(new NpcQuestPageDeps.CompletionToast() {
                    @Override
                    @Nullable
                    public ToastSpec forCompleted(@Nonnull Quest quest) {
                        return ToastSpec.of(ToastKind.SUCCESS, Msg.raw("rows unknown"));
                    }

                    @Override
                    @Nullable
                    public ToastSpec forCompleted(@Nonnull Quest quest,
                            @Nonnull List<RewardSpec> rewards) {
                        return ToastSpec.of(ToastKind.REWARD, Msg.raw("mine"))
                                .withLines(RewardToastLines.lines(rewards, naming(), null));
                    }
                })
                .build();

        ToastSpec spec = deps.resolveCompletionToast(Quest.builder("q").build(), ROLLED,
                Msg.raw("Quest complete"), null);

        assertEquals(ToastKind.REWARD, spec.kind());
        assertEquals(2, spec.lines().size(), "the rows the page decided on, painted by the fill");
    }

    @Test
    void aConsumerToastThatThrowsCostsItsOwnLineOnly() {
        NpcQuestPageDeps deps = NpcQuestPageDeps.builder()
                .rewardChips(naming())
                .completionToast(quest -> {
                    throw new IllegalStateException("boom");
                })
                .build();

        ToastSpec spec = deps.resolveCompletionToast(Quest.builder("q").build(), ROLLED,
                Msg.raw("Quest complete"), null);

        assertEquals(ToastKind.REWARD, spec.kind());
        assertEquals(2, spec.lines().size(), "the library line, with the receipt under it");
    }

    // ==================== the deps supplier ====================

    @Test
    void anUnregisteredConsumerGetsTheLibraryDefaults() {
        assertSame(NpcQuestPageDeps.DEFAULTS, NpcQuestPages.resolvedDeps());
    }

    @Test
    void aRegisteredSupplierIsAskedEveryTimeItIsNeeded() {
        NpcQuestPageDeps mine = NpcQuestPageDeps.builder().build();
        NpcQuestPages.deps(() -> mine);
        assertSame(mine, NpcQuestPages.resolvedDeps());
    }

    @Test
    void aSupplierThatFailsFallsBackRatherThanTakingTheScreenDown() {
        NpcQuestPages.deps(() -> null);
        assertSame(NpcQuestPageDeps.DEFAULTS, NpcQuestPages.resolvedDeps());
        NpcQuestPages.deps(() -> {
            throw new IllegalStateException("boom");
        });
        assertNotNull(NpcQuestPages.resolvedDeps());
        assertSame(NpcQuestPageDeps.DEFAULTS, NpcQuestPages.resolvedDeps());
    }
}
