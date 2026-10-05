package com.ziggfreed.common.npc.placement.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.npc.placement.runtime.AnchorSections.Step;
import com.ziggfreed.common.world.TickingSections.SectionPos;
import com.ziggfreed.common.world.TickingSections.State;

/**
 * One sweep round's rule for the chunk section under an anchor (Update 7): a placement goes in only where
 * the section ticks AND this round did not wake it.
 */
class AnchorSectionsTest {

    private static final SectionPos HUB = new SectionPos(2, 3, 9);
    private static final SectionPos TEMPLE = new SectionPos(10, 2, 9);

    @Test
    void aTickingSectionThisRoundDidNotWakeIsReady() {
        assertEquals(Step.READY, new AnchorSections().stepFor(HUB, State.TICKING));
    }

    @Test
    void parkedSpawnRegression_aSleepingSectionInMemoryIsWokenAndNeverReady() {
        // X29: the boot sweep placed the hub NPC into its spawn section while that section slept (its
        // column ticked), so the engine parked it and the ledger never heard of it; every later pass
        // parked another. A sleeping section is woken, never placed into.
        AnchorSections round = new AnchorSections();
        assertEquals(Step.WAKE, round.stepFor(HUB, State.PARKING));
        assertNotEquals(Step.READY, round.stepFor(HUB, State.PARKING));
    }

    @Test
    void aSectionNotInMemoryIsLoaded() {
        assertEquals(Step.LOAD, new AnchorSections().stepFor(HUB, State.ABSENT));
    }

    @Test
    void aSectionThisRoundWokeWaitsForTheNextRoundThoughItTicksNow() {
        // The wake brought the section's parked NPCs back after this round's despawn pass ran, so a copy
        // an earlier build parked there is resident but not yet adopted: placing now would stand a second
        // one beside it.
        AnchorSections round = new AnchorSections();
        round.woke(HUB);
        assertEquals(Step.WAIT, round.stepFor(HUB, State.TICKING));
        assertTrue(round.wokeAny());
        assertEquals(Step.READY, new AnchorSections().stepFor(HUB, State.TICKING), "the next round places");
    }

    @Test
    void aWakeHoldsBackOnlyItsOwnSection() {
        AnchorSections round = new AnchorSections();
        assertFalse(round.wokeAny());
        round.woke(HUB);
        assertEquals(Step.READY, round.stepFor(TEMPLE, State.TICKING));
    }
}
