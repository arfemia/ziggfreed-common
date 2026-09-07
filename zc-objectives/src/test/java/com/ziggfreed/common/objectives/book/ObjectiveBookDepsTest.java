package com.ziggfreed.common.objectives.book;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.loot.reward.RewardGrants;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.quest.Quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The book's consumer seams: every default leaves a working page, a null fill falls back to the
 * default rather than a null field, and a seam that throws costs its own answer, never the page.
 */
class ObjectiveBookDepsTest {

    @Test
    void defaultsLeaveEverySeamWorking() {
        ObjectiveBookDeps deps = ObjectiveBookDeps.DEFAULTS;
        Quest quest = Quest.builder("q").build();
        assertFalse(deps.managedGuarded(quest));
        assertTrue(deps.pillsGuarded(quest).isEmpty());
        assertNull(deps.acceptHintGuarded(quest));
        assertNull(deps.claimOfGuarded("first_kill", UUID.randomUUID()));
        assertNotNull(deps.tagLabelGuarded("wilds_side"));
        assertEquals(ObjectiveBookDeps.MilestoneClaimOutcome.NOT_READY,
                ObjectiveBookDeps.NO_MILESTONE_CLAIM.claim(100, null, null, null));
        assertNull(deps.claimPreCheckGuarded(quest, null, null, null));
        assertFalse(deps.announcesActions());
    }

    @Test
    void aFilledFeedbackSeamOwnsTheAnnouncement() {
        ObjectiveBookDeps deps = ObjectiveBookDeps.builder()
                .actionFeedback(new ObjectiveBookDeps.ActionFeedback() {
                })
                .build();
        assertTrue(deps.announcesActions());
        assertFalse(ObjectiveBookDeps.builder().actionFeedback(null).build().announcesActions());
    }

    @Test
    void nullFillsFallBackToDefaults() {
        ObjectiveBookDeps deps = ObjectiveBookDeps.builder()
                .theme(null)
                .railPainter(null)
                .sidePanelPainter(null)
                .extHandler(null)
                .boardManaged(null)
                .requirementText(null)
                .tagLabels(null)
                .rewardChips(null)
                .firstClaims(null)
                .milestones(null)
                .milestoneClaim(null)
                .actionFeedback(null)
                .claimPreCheck(null)
                .build();
        assertSame(ObjectiveBookDeps.NO_CHROME, deps.railPainter());
        assertSame(ObjectiveBookDeps.NO_EXT, deps.extHandler());
        assertSame(ObjectiveBookDeps.NO_BOARDS, deps.boardManaged());
        assertSame(ObjectiveBookDeps.NO_FIRST_CLAIMS, deps.firstClaims());
        assertSame(ObjectiveBookDeps.NO_MILESTONES, deps.milestones());
        assertSame(ObjectiveBookDeps.NO_MILESTONE_CLAIM, deps.milestoneClaim());
        assertSame(ObjectiveBookDeps.NO_FEEDBACK, deps.actionFeedback());
        assertSame(ObjectiveBookDeps.NO_CLAIM_PRECHECK, deps.claimPreCheck());
    }

    @Test
    void throwingSeamsCostTheirOwnAnswerOnly() {
        ObjectiveBookDeps deps = ObjectiveBookDeps.builder()
                .boardManaged(new ObjectiveBookDeps.BoardManagedQuests() {
                    @Override
                    public boolean managed(Quest quest) {
                        throw new IllegalStateException("boom");
                    }

                    @Override
                    public List<ObjectiveBookDeps.Pill> pills(Quest quest) {
                        throw new IllegalStateException("boom");
                    }
                })
                .requirementText(quest -> {
                    throw new IllegalStateException("boom");
                })
                .tagLabels(tag -> {
                    throw new IllegalStateException("boom");
                })
                .firstClaims((achievementId, viewer) -> {
                    throw new IllegalStateException("boom");
                })
                .milestones((store, ref, subject) -> {
                    throw new IllegalStateException("boom");
                })
                .claimPreCheck((q, store, ref, player) -> {
                    throw new IllegalStateException("boom");
                })
                .build();
        Quest quest = Quest.builder("q").build();
        assertFalse(deps.managedGuarded(quest));
        assertTrue(deps.pillsGuarded(quest).isEmpty());
        assertNull(deps.requirementLineGuarded(quest));
        assertNotNull(deps.tagLabelGuarded("epic"));
        assertNull(deps.claimOfGuarded("first_kill", null));
        assertTrue(deps.milestonesGuarded(null, null, null).isEmpty());
        assertNull(deps.claimPreCheckGuarded(quest, null, null, null));
    }

    @Test
    void prettifyReadsAuthoredIdsAsWords() {
        assertEquals("Wilds Side", ObjectiveBookDeps.prettifyTag("wilds_side"));
        assertEquals("Boss Fights", ObjectiveBookDeps.prettifyTag("boss-fights"));
    }

    // ==================== the milestone Collect: what the toast lists ====================

    private static final List<RewardSpec> AUTHORED = List.of(
            RewardSpec.of("Lootable", Map.of("Id", "Milestone_Crate")));
    private static final List<RewardSpec> ROLLED = List.of(
            RewardSpec.of("Item", Map.of("Item", "Coin_Gold", "Count", "3")),
            RewardSpec.of("Item", Map.of("Item", "Gem_Ruby", "Count", "1")));

    /** A fill that answers only the outcome, the way every fill did before the receipt existed. */
    @Test
    void aFillAnsweringOnlyTheOutcomeStillWorksAndTheBookListsTheRungAsAuthored() {
        ObjectiveBookDeps.MilestoneClaim legacy = (threshold, store, ref, player) ->
                ObjectiveBookDeps.MilestoneClaimOutcome.SUCCESS;

        ObjectiveBookDeps.MilestoneClaimResult result = legacy.tryClaim(100, null, null, null);

        assertEquals(ObjectiveBookDeps.MilestoneClaimOutcome.SUCCESS, result.outcome());
        assertNull(result.receipt(), "no word on what was handed over");
        assertEquals(AUTHORED, result.rowsOr(AUTHORED),
                "so the toast falls back to the rung as authored, resolved before the claim");
        assertEquals(ObjectiveBookDeps.MilestoneClaimOutcome.NOT_READY,
                ObjectiveBookDeps.NO_MILESTONE_CLAIM.tryClaim(100, null, null, null).outcome());
    }

    /** A fill holding the payout's receipt lists what was PAID, never the rung's own list. */
    @Test
    void aFillAnsweringTheReceiptListsWhatWasPaid() {
        ObjectiveBookDeps.MilestoneClaim paying = new ObjectiveBookDeps.MilestoneClaim() {
            @Override
            @Nonnull
            public ObjectiveBookDeps.MilestoneClaimOutcome claim(int threshold,
                    @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                    @Nonnull Player player) {
                return tryClaim(threshold, store, ref, player).outcome();
            }

            @Override
            @Nonnull
            public ObjectiveBookDeps.MilestoneClaimResult tryClaim(int threshold,
                    @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                    @Nonnull Player player) {
                return ObjectiveBookDeps.MilestoneClaimResult.paid(ROLLED);
            }
        };

        ObjectiveBookDeps.MilestoneClaimResult result = paying.tryClaim(100, null, null, null);

        assertEquals(ObjectiveBookDeps.MilestoneClaimOutcome.SUCCESS, result.outcome());
        assertEquals(ROLLED, result.rowsOr(AUTHORED), "a rolled table reads as the items it rolled");
        assertEquals(ObjectiveBookDeps.MilestoneClaimOutcome.SUCCESS, paying.claim(100, null, null, null));
        assertTrue(ObjectiveBookDeps.MilestoneClaimResult.paid(List.of()).rowsOr(AUTHORED).isEmpty(),
                "an empty roll lists nothing, never the authored list");
    }

    // ==================== the accept feedback: a fill that ignores the payout ====================

    /** The book announces with the settle's outcome; a fill written for the plain form still hears it. */
    @Test
    void aFeedbackFillThatIgnoresThePayoutStillHearsTheAccept() {
        List<String> heard = new ArrayList<>();
        ObjectiveBookDeps.ActionFeedback plain = new ObjectiveBookDeps.ActionFeedback() {
            @Override
            public void accepted(@Nonnull Quest quest, @Nonnull Store<EntityStore> store,
                    @Nonnull Ref<EntityStore> ref, @Nonnull Player player) {
                heard.add(quest.id());
            }
        };
        Quest quest = Quest.builder("q_settled").build();

        plain.accepted(quest, null, null, null, new RewardGrants.GrantOutcome(1, 0, 0, ROLLED));
        plain.accepted(quest, null, null, null, null);

        assertEquals(List.of("q_settled", "q_settled"), heard);
    }
}
