package com.ziggfreed.common.proc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.protocol.InteractionState;

/**
 * The decisions a paid move rests on, pure. A dodge counts only when a dodge effect lands after the press: one not on
 * before, a new instance, or one whose time grew; a press that left the old effect running down (a refused dodge, a
 * forward press inside an earlier dodge's quarter second) does not. A cast counts once its chain finished or is still
 * running past its opening, never one that failed.
 */
class MoveProofsTest {

    private static final int LEFT = 7;
    private static final int GUARD = 9;

    @Test
    void aDodgeEffectLandingAfterThePress_isAPaidDodge() {
        Object instance = new Object();
        assertTrue(MoveProofs.gained(Map.of(), Map.of(LEFT, new MoveProofs.Mark(instance, 0.25f))),
                "none before, one now: the dodge went through");
        assertTrue(MoveProofs.gained(Map.of(LEFT, new MoveProofs.Mark(new Object(), 0.05f)),
                Map.of(LEFT, new MoveProofs.Mark(instance, 0.25f))), "a new instance of it lands");
        assertTrue(MoveProofs.gained(Map.of(LEFT, new MoveProofs.Mark(instance, 0.10f)),
                Map.of(LEFT, new MoveProofs.Mark(instance, 0.35f))), "a second apply that adds its time");
    }

    @Test
    void aPressThatPaidNothing_isNoDodge() {
        Object instance = new Object();
        assertFalse(MoveProofs.gained(Map.of(), Map.of()), "nothing landed: refused, or a forward or back press");
        assertFalse(MoveProofs.gained(Map.of(LEFT, new MoveProofs.Mark(instance, 0.20f)),
                Map.of(LEFT, new MoveProofs.Mark(instance, 0.15f))), "an earlier dodge's effect running down");
        assertFalse(MoveProofs.gained(Map.of(LEFT, new MoveProofs.Mark(instance, 0.20f), GUARD,
                new MoveProofs.Mark(new Object(), 0.20f)), Map.of(LEFT, new MoveProofs.Mark(instance, 0.15f))),
                "one running out is no landing");
    }

    @Test
    void aCastCountsOnceItIsPastItsOpening_neverOnceItFailed() {
        assertEquals(MoveProofs.Verdict.PROVEN, MoveProofs.castVerdict(InteractionState.Finished, 0L));
        assertEquals(MoveProofs.Verdict.WAIT, MoveProofs.castVerdict(InteractionState.NotFinished,
                MoveProofs.CAST_PROOF_NANOS - 1));
        assertEquals(MoveProofs.Verdict.PROVEN, MoveProofs.castVerdict(InteractionState.NotFinished,
                MoveProofs.CAST_PROOF_NANOS), "still running past its opening: its cost check passed");
        assertEquals(MoveProofs.Verdict.DROPPED, MoveProofs.castVerdict(InteractionState.Failed, 0L),
                "a refused cost check fails the chain");
        assertEquals(MoveProofs.Verdict.DROPPED, MoveProofs.castVerdict(InteractionState.Failed,
                MoveProofs.CAST_PROOF_NANOS * 4));
    }
}
