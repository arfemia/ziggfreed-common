package com.ziggfreed.common.npc.placement.runtime;

import java.util.HashSet;
import java.util.Set;

import javax.annotation.Nonnull;

import com.ziggfreed.common.world.TickingSections.SectionPos;
import com.ziggfreed.common.world.TickingSections.State;

/**
 * One sweep round's rule for the chunk section under an anchor, on Update 7, where an NPC stays in the
 * world only in a TICKING section and a section ticks on its own, whatever its column does (zc-world's
 * {@code TickingSections}).
 *
 * <p>A placement goes in only where the section ticks AND this round did not wake it. A wake brings the
 * section's parked NPCs back into the world after the round's despawn pass has run, so a copy an earlier
 * build parked there is resident but not yet adopted, and placing in the same round would stand a second
 * one beside it; the sweep's next round ({@code NpcPlacementReconciler.settleRounds}) adopts first and
 * then places. A fresh instance per round; world thread only, so no locking.
 */
final class AnchorSections {

    /** What a round does about one anchor's section. */
    enum Step {
        /** The section ticks and this round did not wake it: an NPC placed there stays. */
        READY,
        /** In memory and asleep: wake it now, on the world thread, and place on the next round. */
        WAKE,
        /** Not in memory: ask for it ticking; its landing sweeps the world again. */
        LOAD,
        /** This round woke it: its parked NPCs just came back, and only the next round counts them. */
        WAIT
    }

    private final Set<SectionPos> woken = new HashSet<>();

    /** The step for {@code section}, read as {@code state} this round. */
    @Nonnull
    Step stepFor(@Nonnull SectionPos section, @Nonnull State state) {
        if (woken.contains(section)) {
            return Step.WAIT;
        }
        return switch (state) {
            case TICKING -> Step.READY;
            case PARKING -> Step.WAKE;
            case ABSENT -> Step.LOAD;
        };
    }

    /** This round woke {@code section}: hold it back until the next round. */
    void woke(@Nonnull SectionPos section) {
        woken.add(section);
    }

    /** Whether this round woke any section, the signal for the sweep's second round. */
    boolean wokeAny() {
        return !woken.isEmpty();
    }
}
