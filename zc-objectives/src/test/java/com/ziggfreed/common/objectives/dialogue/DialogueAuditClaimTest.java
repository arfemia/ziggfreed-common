package com.ziggfreed.common.objectives.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.dialogue.DialogueEngine;
import com.ziggfreed.common.dialogue.schema.DialogueOption;
import com.ziggfreed.common.dialogue.schema.DialogueTypeTable;
import com.ziggfreed.common.dialogue.schema.NpcDialogue;
import com.ziggfreed.common.dialogue.type.DialogueCondition;
import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.objectives.runtime.ProgressionDefaults;
import com.ziggfreed.common.progress.runtime.ProgressionGates;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.validation.Finding;

/**
 * The conversation audit's once-per-boot claim, the seam that keeps a server running a consumer that
 * reports the same findings from hearing each one twice: a consumer's claim leaves zc's boot audit
 * nothing and the first join one line naming who reports them, and a spent run prints nothing more.
 * Only the claimed paths are driven here: an unclaimed run walks the live stores and builds the
 * progression runtime, which a booted server proves. Beside them, the pure audit's reading of a
 * {@code Requires} block on a line its caller holds shut.
 */
class DialogueAuditClaimTest {

    /** The feature a consumer has switched off, named by the line's own block. */
    private static final String SWITCHED_OFF = "off_feature";

    @BeforeEach
    void rearm() {
        DialogueAudit.clearLateAuditForTests();
    }

    @AfterEach
    void reset() {
        DialogueAudit.clearLateAuditForTests();
    }

    @Test
    void aClaimedRunLeavesTheBootAuditNothingAndTheFirstJoinOneLine() {
        DialogueAudit.claimLateAudit("  SomeConsumer  ");
        assertEquals("SomeConsumer", DialogueAudit.lateAuditOwner());
        assertEquals(List.of(), DialogueAudit.claimLateFindings(), "the claimant reports the findings itself");

        List<String> errors = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        DialogueAudit.runLateAudit(errors::add, notes::add);
        DialogueAudit.runLateAudit(errors::add, notes::add);

        assertEquals(List.of(), errors);
        assertEquals(List.of(DialogueAudit.LOG_LABEL + " content findings are reported by SomeConsumer"), notes,
                "the run is spent once, naming who reports the findings");
    }

    @Test
    void aBlankClaimClaimsNothing() {
        DialogueAudit.claimLateAudit("   ");
        assertEquals(null, DialogueAudit.lateAuditOwner());
    }

    /**
     * A line held shut by a feature this server has switched off names ids that are not this server's
     * content: a conversation shipping a hook for a pack that is not installed must not report the pack's
     * quest ids as missing, nor the prerequisite its own block names. The block itself is still read, so a
     * mistake in it is still caught, and the same line on a feature switched ON is ordinary content and is
     * audited, its block's prerequisite through the shared gate audit. Both spellings, and each use of the
     * deprecated {@code Gate} is reported naming {@code Requires}, on a line held shut too.
     */
    @Test
    void aLineHeldShutIsNotAuditedForQuestsWhileItsRequiresBlockStillIs() {
        resetVocabulary();
        try {
            // The library's own setup: the factor vocabulary a block is answered with, and the conditions.
            ProgressionDefaults.register();
            DialogueBootstrap.registerDialogueVocabulary();
            String body = """
                    { "Start": { "Fallback": "a" },
                      "Nodes": { "a": { "TextKey": "k", "Options": [
                        { "LabelKey": "l",
                          "Conditions": [ { %s: { "Quests": ["pack_only_prerequisite"], "Factors": [
                            { "Factor": "zcaudittest:feature", "Param": "%s", "Min": 1 } ] } } ],
                          "Actions": [ { "Type": "AcceptQuest", "Quest": "pack_only_quest" } ] } ] } } }
                    """;
            for (String spelling : List.of("\"Type\": \"Requires\", \"Requires\"",
                    "\"Type\": \"Gate\", \"Requirements\"")) {
                boolean deprecated = spelling.contains("Gate");
                List<Finding> off = audit(body.formatted(spelling, SWITCHED_OFF));
                List<Finding> on = audit(body.formatted(spelling, "on_feature"));

                assertFalse(has(off, DialogueAudit.UNKNOWN_QUEST_ID),
                        "a switched-off feature holds the line shut, so its quest id is not audited: " + spelling);
                assertFalse(has(off, "UNKNOWN_PREREQUISITE"), "nor the prerequisite its block names: " + spelling);
                assertTrue(has(off, "UNKNOWN_FACTOR"), "while the block itself is still read: " + spelling);
                assertTrue(has(on, DialogueAudit.UNKNOWN_QUEST_ID),
                        "the same line behind a switched-ON feature is ordinary content and is audited: " + spelling);
                assertTrue(has(on, "UNKNOWN_PREREQUISITE"), "its block's prerequisite too: " + spelling);
                assertEquals(deprecated, has(off, DialogueAudit.GATE_CONDITION_DEPRECATED),
                        "the deprecated spelling is reported on a line held shut: " + spelling);
                assertEquals(deprecated, has(on, DialogueAudit.GATE_CONDITION_DEPRECATED),
                        "and on an open one, and Requires never is: " + spelling);
            }
        } finally {
            resetVocabulary();
        }
    }

    // ==================== helpers ====================

    @Nonnull
    private static List<Finding> audit(@Nonnull String body) {
        NpcDialogue dialogue = DialogueEngine.shared().decode("held_shut", body);
        assertNotNull(dialogue, "the library's own vocabulary reads both spellings");
        return DialogueAudit.auditConversations(List.of(dialogue), DialogueEngine.shared(), questId -> false,
                DialogueAuditClaimTest::switchedOff);
    }

    /** A consumer's rule for a line it holds shut: the line's own Requires block needs a feature it switched off. */
    private static boolean switchedOff(@Nonnull DialogueOption option) {
        for (DialogueCondition condition : option.getConditions()) {
            if (condition instanceof RequiresDialogueCondition.Block block) {
                for (FactorCondition factor : block.getRequires().factorsOrEmpty()) {
                    if (factor != null && SWITCHED_OFF.equals(factor.getParam())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean has(@Nonnull List<Finding> findings, @Nonnull String code) {
        return findings.stream().anyMatch(f -> f.code().equals(code));
    }

    private static void resetVocabulary() {
        ProgressionRuntime.resetForTests();
        ProgressionDefaults.reset();
        ProgressionGates.resetForTests();
        DialogueTypeTable.get().resetForTests();
        DialogueEngine.resetSharedForTests();
    }
}
