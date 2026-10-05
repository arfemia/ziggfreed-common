package com.ziggfreed.common.encounter.run;

import javax.annotation.Nonnull;

/**
 * What becomes of one add waiting on its encounter's tick to be scaled. An add is noted the moment
 * the encounter's lineage lands on it, before the engine has built its stats, so the tick decides:
 * scale it now, look again next tick, or let it go as its role made it.
 */
public final class AddScaling {

    /** How many ticks an add may wait for its health stat before it is let go unscaled. */
    public static final int MAX_WAIT_TICKS = 100;

    /** One add's next step. */
    public enum Step {
        /** Scale it now, once. */
        APPLY,
        /** Its health is not built yet: look again next tick. */
        WAIT,
        /** Leave it as its role made it. */
        DROP
    }

    private AddScaling() {
    }

    /**
     * @param scales     whether the run scales adds at all: an unsettled run whose row's add scale is above 1
     * @param present    whether the add is still in the world
     * @param isSubject  whether it is the run's own subject, which carries the encounter's lineage too
     * @param statsReady whether its health stat is built
     * @param waited     how many ticks it has already waited
     */
    @Nonnull
    public static Step step(boolean scales, boolean present, boolean isSubject, boolean statsReady, int waited) {
        if (!scales || !present || isSubject) {
            return Step.DROP;
        }
        if (statsReady) {
            return Step.APPLY;
        }
        return waited + 1 >= MAX_WAIT_TICKS ? Step.DROP : Step.WAIT;
    }
}
