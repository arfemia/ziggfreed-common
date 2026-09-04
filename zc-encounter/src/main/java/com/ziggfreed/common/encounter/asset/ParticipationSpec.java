package com.ziggfreed.common.encounter.asset;

import java.util.function.BiFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.encounter.ledger.ParticipationWeights;
import com.ziggfreed.common.factor.FactorFormula;

/**
 * The credit rules one run actually uses: the matched {@link EncounterParticipationAsset} with the
 * binding row's own {@link EncounterBindingAsset.Participation} laid over it leaf by leaf, and the
 * library's structural posture underneath both for any leaf neither authored.
 *
 * <p>The structural posture is deliberately plain: damage dealt counts one for one, nothing else
 * counts, nobody is cut off and everybody keeps their share. It is what a server with no rule file
 * at all gets, and it is the posture rather than the content: the numbers a server ships with are
 * the library's own {@code Zc_Default} rule file, which a pack or an owner overrides by id.
 *
 * @param damageDealt        the weight of one point of damage dealt, as a formula
 * @param damageTaken        the weight of one point of damage taken, as a formula
 * @param presence           the weight of one second of presence, as a formula
 * @param minShare           the share under which a participant is credited but paid nothing
 * @param creditDead         whether a participant who died keeps their share
 * @param creditDisconnected whether a participant offline at the payout keeps their share
 */
public record ParticipationSpec(@Nullable FactorFormula damageDealt, @Nullable FactorFormula damageTaken,
                                @Nullable FactorFormula presence, double minShare, boolean creditDead,
                                boolean creditDisconnected) {

    /** The posture with nothing authored: dealt counts one for one, nothing else does. */
    public static final ParticipationSpec STRUCTURAL = new ParticipationSpec(
            FactorFormula.of(1.0, null, null), null, null, 0.0, true, true);

    /** {@code rule} with {@code override} laid over it leaf by leaf; either side may be absent. */
    @Nonnull
    public static ParticipationSpec of(@Nullable EncounterParticipationAsset rule,
            @Nullable EncounterBindingAsset.Participation override) {
        FactorFormula dealt = STRUCTURAL.damageDealt();
        FactorFormula taken = STRUCTURAL.damageTaken();
        FactorFormula presence = STRUCTURAL.presence();
        double minShare = STRUCTURAL.minShare();
        boolean creditDead = STRUCTURAL.creditDead();
        boolean creditDisconnected = STRUCTURAL.creditDisconnected();
        if (rule != null) {
            dealt = rule.getDamageDealt() != null ? rule.getDamageDealt() : dealt;
            taken = rule.getDamageTaken() != null ? rule.getDamageTaken() : taken;
            presence = rule.getPresence() != null ? rule.getPresence() : presence;
            minShare = rule.getMinShare() != null ? rule.getMinShare() : minShare;
            creditDead = rule.getCreditDead() != null ? rule.getCreditDead() : creditDead;
            creditDisconnected = rule.getCreditDisconnected() != null
                    ? rule.getCreditDisconnected() : creditDisconnected;
        }
        if (override != null) {
            dealt = override.getDamageDealt() != null ? override.getDamageDealt() : dealt;
            taken = override.getDamageTaken() != null ? override.getDamageTaken() : taken;
            presence = override.getPresence() != null ? override.getPresence() : presence;
            minShare = override.getMinShare() != null ? override.getMinShare() : minShare;
            creditDead = override.getCreditDead() != null ? override.getCreditDead() : creditDead;
            creditDisconnected = override.getCreditDisconnected() != null
                    ? override.getCreditDisconnected() : creditDisconnected;
        }
        return new ParticipationSpec(dealt, taken, presence, clamp01(minShare), creditDead, creditDisconnected);
    }

    /**
     * The three weights for one participant, each formula evaluated through {@code lookup}
     * ({@code (factorId, param) -> value}, null for "cannot answer"); an absent formula weighs
     * zero, and a formula that resolves to a negative number weighs zero too, because a counter
     * cannot subtract credit.
     */
    @Nonnull
    public ParticipationWeights weightsFor(@Nonnull BiFunction<String, String, Double> lookup) {
        return new ParticipationWeights(weight(damageDealt, lookup), weight(damageTaken, lookup),
                weight(presence, lookup));
    }

    /** True when presence can never accrue credit under this spec (a presence formula that reads zero). */
    public boolean presenceWeighsNothing() {
        return presence == null || (presence.isEmpty());
    }

    private static double weight(@Nullable FactorFormula formula, @Nonnull BiFunction<String, String, Double> lookup) {
        if (formula == null) {
            return 0.0;
        }
        double value = formula.evaluate(lookup);
        return Double.isFinite(value) && value > 0.0 ? value : 0.0;
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            return 0.0;
        }
        return Math.min(1.0, value);
    }
}
