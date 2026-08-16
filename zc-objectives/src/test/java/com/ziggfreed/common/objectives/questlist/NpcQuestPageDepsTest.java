package com.ziggfreed.common.objectives.questlist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.loot.reward.RewardSpec;

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
                "quest", "Guide", null, null, null, null),
                "nothing follows a settled quest, so the page keeps the screen");
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
        assertSame(NpcQuestPageDeps.NO_HAND_OFF, deps.completion());
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
