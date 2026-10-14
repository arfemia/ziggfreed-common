package com.ziggfreed.common.encounter.ledger;

/**
 * How much one unit of each channel is worth to one participant: a point of damage dealt to the
 * subject, a point of damage taken while a member, a second spent as a member, a point of damage
 * dealt to one of the fight's adds. Non-negative by construction; a negative weight reads as zero
 * because a counter cannot subtract credit.
 */
public record ParticipationWeights(double damageDealt, double damageTaken, double presence, double damageToAdds) {

    /** Damage dealt counts one for one and nothing else counts: the structural posture. */
    public static final ParticipationWeights DEALT_ONLY = new ParticipationWeights(1.0, 0.0, 0.0, 0.0);

    public ParticipationWeights {
        damageDealt = clean(damageDealt);
        damageTaken = clean(damageTaken);
        presence = clean(presence);
        damageToAdds = clean(damageToAdds);
    }

    /** The first three channels, damage to the adds weighing nothing. */
    public ParticipationWeights(double damageDealt, double damageTaken, double presence) {
        this(damageDealt, damageTaken, presence, 0.0);
    }

    /** True when seconds spent inside earn nothing under these weights. */
    public boolean presenceWeighsNothing() {
        return presence <= 0.0;
    }

    private static double clean(double value) {
        return Double.isFinite(value) && value > 0.0 ? value : 0.0;
    }
}
