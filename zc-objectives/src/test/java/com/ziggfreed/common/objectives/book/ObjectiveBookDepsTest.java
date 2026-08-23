package com.ziggfreed.common.objectives.book;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

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
}
