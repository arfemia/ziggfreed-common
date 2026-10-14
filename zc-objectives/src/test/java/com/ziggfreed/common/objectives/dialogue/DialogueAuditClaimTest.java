package com.ziggfreed.common.objectives.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The conversation audit's once-per-boot claim, the seam that keeps a server running a consumer that
 * reports the same findings from hearing each one twice: a consumer's claim leaves zc's boot audit
 * nothing and the first join one line naming who reports them, and a spent run prints nothing more.
 * Only the claimed paths are driven here: an unclaimed run walks the live stores and builds the
 * progression runtime, which a booted server proves.
 */
class DialogueAuditClaimTest {

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
}
