package com.ziggfreed.common.objectives.questlist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.loot.reward.RewardChip;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.objectives.book.ObjectiveBookDeps;
import com.ziggfreed.common.objectives.book.ObjectiveBookPages;
import com.ziggfreed.common.objectives.journal.QuestPresentation;
import com.ziggfreed.common.quest.Quest;

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
        ObjectiveBookPages.deps(null);
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
                .rewardChips(null).completion(null)
                .build();
        assertSame(NpcQuestPageDeps.ASSET_ANSWER_SETS, deps.answerSets());
        assertSame(NpcQuestPageDeps.ASSET_NAMES, deps.npcNames());
        assertSame(NpcQuestPageDeps.PLAIN_THEME, deps.theme());
        assertSame(NpcQuestPageDeps.GENERIC_CHIPS, deps.rewardChips());
        assertSame(NpcQuestPageDeps.ENGINE_HAND_OFF, deps.completion());
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

    // ==================== how a quest reads ====================

    /** A chip reading that names every reward, so a chip is readable with no server. */
    @Nonnull
    private static NpcQuestPageDeps.RewardChipSource naming() {
        return spec -> RewardChip.text(Msg.raw(spec.paramOr("Item", "?")));
    }

    /**
     * Smoke 18: a quest reads at its giver exactly as it reads in the book, so the page reads it through the book's
     * consumer seams (a board's quests, tag names), with its own reward reading on top.
     */
    @Test
    void aQuestReadsAtItsGiverAsTheBookReadsIt() {
        ObjectiveBookPages.deps(() -> ObjectiveBookDeps.builder()
                .boardManaged(quest -> "q_board".equals(quest.id()))
                .tagLabels(tag -> Msg.raw("Tag " + tag))
                .build());
        NpcQuestPageDeps deps = NpcQuestPageDeps.builder().rewardChips(naming()).build();

        QuestPresentation presentation = deps.presentation();

        assertTrue(presentation.managed(Quest.builder("q_board").build()), "the book's board manages it here too");
        assertFalse(presentation.managed(Quest.builder("q_other").build()));
        assertEquals("Tag x", presentation.tagLabel("x").getRawText(), "the book's tag names");
        assertEquals("Coin_Gold", presentation.rewardChips()
                .chipFor(RewardSpec.of("Item", Map.of("Item", "Coin_Gold"))).label().getRawText(),
                "the page's own reward reading");
    }

    @Test
    void withNoBookConsumerAQuestReadsAsTheLibrarysBook() {
        Quest quest = Quest.builder("q").build();
        QuestPresentation presentation = NpcQuestPageDeps.DEFAULTS.presentation();

        assertFalse(presentation.managed(quest), "nothing is board-managed on a bare server");
        assertNull(presentation.acceptHint(quest));
        assertTrue(presentation.pills(quest).isEmpty());
    }

    @Test
    void aBookWhoseDepsFailCostsTheBooksSeamsNotThePage() {
        ObjectiveBookPages.deps(() -> {
            throw new IllegalStateException("boom");
        });

        assertFalse(NpcQuestPageDeps.DEFAULTS.presentation().managed(Quest.builder("q").build()));
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
