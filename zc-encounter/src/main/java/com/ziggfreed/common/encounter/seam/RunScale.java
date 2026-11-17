package com.ziggfreed.common.encounter.seam;

/**
 * How much tougher the run a fight stands in makes it, as {@link EncounterRunScale} answers it: its boss's and every
 * add's maximum health times {@code health}, their every hit times {@code damage} (a dungeon tier's step, say).
 * Both are held at 1 or more, so a step only ever makes a fight harder.
 *
 * @param health the health multiplier, 1 or more
 * @param damage the damage multiplier, 1 or more
 */
public record RunScale(double health, double damage) {

    /** No step: what a fight in no run, or with no fill, reads. */
    public static final RunScale NONE = new RunScale(1.0, 1.0);

    public RunScale {
        health = atLeastOne(health);
        damage = atLeastOne(damage);
    }

    /** Whether this step changes nothing. */
    public boolean none() {
        return health == 1.0 && damage == 1.0;
    }

    /** The whole percent {@code damage} raises a hit by: 1.3 is 30; 0 for no step. */
    public int damagePercent() {
        return (int) Math.round((damage - 1.0) * 100.0);
    }

    private static double atLeastOne(double value) {
        return Double.isFinite(value) ? Math.max(1.0, value) : 1.0;
    }
}
