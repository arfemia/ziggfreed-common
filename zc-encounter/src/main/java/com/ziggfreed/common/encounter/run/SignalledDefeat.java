package com.ziggfreed.common.encounter.run;

import javax.annotation.Nonnull;

/**
 * What a script's {@code zc:defeated} beat means for a run, decided from what this library can SEE
 * of the subject rather than from the beat alone.
 *
 * <p>A script's defeat beat sits under a {@code Not(Target)} sensor, and the engine's target sensor
 * lets go of its boss for three different reasons: the boss died, the boss left the world without
 * dying (a despawn, a marker storing it away), or the boss ran past the sensor's {@code Range}. Only
 * the first is a defeat. The death system already settles a real death the instant the subject's
 * death component lands, so a beat that finds a run unsettled with a subject bound is weighed
 * against the world: a subject that reads dead (its corpse still carries its death, or its health is
 * gone) is the defeat; a subject alive, or gone without a death this library saw, was LEASHED: the
 * run is lost, pays nothing, and resets.
 *
 * <p>A fight with no subject bound has nothing to check, so its beat stays its defeat (the shipped
 * example fight signals one with no boss at all).
 */
public final class SignalledDefeat {

    /** What this library sees of the bound subject at the beat. */
    public enum SubjectState {
        /** In the world, with health and no death component. */
        ALIVE,
        /** In the world with a death component, or with no health left. */
        DEAD,
        /** Not in the world at all, or unreadable. */
        GONE
    }

    /** What the beat settles the run as. */
    public enum Verdict {
        /** The subject is down: paid, credited and recorded. */
        DEFEAT,
        /** Lost without a kill: nothing is paid, the run settles as a wipe and resets. */
        LEASH
    }

    private SignalledDefeat() {
    }

    /**
     * The verdict on a {@code zc:defeated} beat for a run that has not settled yet.
     *
     * @param subjectBound whether the run ever bound a subject
     * @param state        what this library sees of that subject now; ignored when none was bound
     */
    @Nonnull
    public static Verdict verdict(boolean subjectBound, @Nonnull SubjectState state) {
        if (!subjectBound) {
            return Verdict.DEFEAT;
        }
        return state == SubjectState.DEAD ? Verdict.DEFEAT : Verdict.LEASH;
    }

    /**
     * The subject's state from three readings: whether its entity is in the world, whether it carries
     * a death component, and its current health ({@code NaN} when unreadable, which reads as alive).
     */
    @Nonnull
    public static SubjectState stateOf(boolean present, boolean deathComponent, float health) {
        if (!present) {
            return SubjectState.GONE;
        }
        if (deathComponent || health <= 0.0F) {
            return SubjectState.DEAD;
        }
        return SubjectState.ALIVE;
    }
}
