package com.ziggfreed.common.proc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/**
 * The arithmetic the third round's moments and groups rest on, pure. A coming hit is read and held the way the engine
 * lands it (rounded to whole points first), so a hit asked to leave someone at 1 health never rounds them to 0; a
 * soak paid out of a stat takes only what the stat holds; a block's spared cost never drops the drain below nothing;
 * an aura's "behind" is the far side of the holder's look; and a per-foe count's weakness is held to its ceiling.
 */
class ProcSoakAndReachTest {

    @Test
    void aComingHit_dropsUnderOnlyWhenItCrossesTheLine_roundedAsTheEngineLandsIt() {
        assertTrue(Procs.dropsUnder(10, 0, 100, 10, 0), "a hit of all that is left kills");
        assertFalse(Procs.dropsUnder(10, 0, 100, 9.4, 0), "9.4 lands as 9: one health is left");
        assertTrue(Procs.dropsUnder(30, 0, 100, 10, 25), "from 30 to 20 crosses a quarter");
        assertFalse(Procs.dropsUnder(100, 0, 100, 30, 25), "from 100 to 70 stays above it");
        assertFalse(Procs.dropsUnder(20, 0, 100, 5, 25), "already under the line: no drop under it");
    }

    @Test
    void aSoaksFloor_leavesAtLeastIt_andAStatPaysOnlyWhatItHolds() {
        assertEquals(49.0, ProcActions.cappedHit(50, 80, 1), 1e-9, "a killing 80 on 50 leaves 1");
        assertEquals(0.0, ProcActions.cappedHit(1, 80, 1), 1e-9, "at the floor already: nothing more lands");
        assertEquals(80.0, ProcActions.cappedHit(50, 80, Double.NaN), 1e-9, "no floor: the hit as it is");
        assertEquals(25.0, ProcActions.paidShare(40, 50, 2), 1e-9, "50 mana at 2 a point buys 25 of 40");
        assertEquals(40.0, ProcActions.paidShare(40, 0, 0), 1e-9, "a free soak takes what it asks");
        assertEquals(0.0, ProcActions.paidShare(40, -3, 2), 1e-9, "a stat under nothing buys nothing");
    }

    @Test
    void aBlocksSparedCost_neverGoesBelowNothing_andABreakIsACostPastWhatIsLeft() {
        assertEquals(0.0, ProcActions.cutDrain(1.0, 1.0), 1e-9, "a whole cut: the block is free");
        assertEquals(0.25, ProcActions.cutDrain(0.5, 0.5), 1e-9, "half of a halved drain");
        assertEquals(0.0, ProcActions.cutDrain(1.0, 3.0), 1e-9, "a cut past whole is whole");
        assertTrue(Procs.breaksGuard(3, 4), "a cost past what is left breaks the guard");
        assertFalse(Procs.breaksGuard(5, 4), "one within it does not");
        assertFalse(Procs.breaksGuard(3, 0), "a block that costs nothing never breaks it");
    }

    @Test
    void behind_isTheFarSideOfTheHoldersLook() {
        Vector3d at = new Vector3d(0, 64, 0);
        double[] lookingNorth = ProcActions.level(0, -2);
        assertTrue(HeldEffects.behind(at, lookingNorth, new Vector3d(0.5, 64, 2)), "south of one looking north");
        assertFalse(HeldEffects.behind(at, lookingNorth, new Vector3d(0, 64, -3)), "in front is not behind");
        assertFalse(HeldEffects.behind(at, lookingNorth, null), "no place, not behind");
        assertEquals(null, ProcActions.level(0, 0), "straight up or down has no level look");
    }

    @Test
    void aPerFoeCount_showsItsCount_andItsWeaknessHeldToTheCeiling() {
        assertEquals(2, FoeMarks.lookFor(3, 5), "three stacks show the third look");
        assertEquals(4, FoeMarks.lookFor(7, 5), "past the list, the last look");
        assertEquals(-1, FoeMarks.lookFor(0, 5), "no stacks, no look");
        assertEquals(6, FoeMarks.percentFor(3, 2), "two percent a stack, three stacks");
        assertEquals(CounterAsset.Weakness.MAX_PERCENT, FoeMarks.percentFor(500, 3), "never past its ceiling");
        assertEquals(9, FoeMarks.valueOf(FoeMarks.weaknessId("mmo_staves_corruption", 9)));
    }

    @Test
    void onlyAHealGiven_waitsForTheProcThatGaveIt() {
        assertTrue(Procs.runsAfter(ProcTrigger.HEAL_GIVEN));
        assertFalse(Procs.runsAfter(ProcTrigger.HIT));
        assertFalse(Procs.runsAfter(ProcTrigger.GUARD_BROKE), "a guard broken waits for the holder pass instead");
    }
}
