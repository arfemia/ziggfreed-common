package com.ziggfreed.common.encounter.wave;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.encounter.run.DamageStep;

/**
 * The damage half of an endless run's ramp: a creature an endless wave raised carries its wave's step as one keyed
 * modifier ({@value #MODIFIER_KEY}) on the every-type damage bonus stat ({@code DamageBonus}, R860), the stat every
 * attacker reads, so the library's own outgoing-damage pass raises every hit it deals by the ramp's percent, summed
 * with its other bonuses as the engine sums Enrage, under its {@code DamageBonusMax}. No effect or asset of its own:
 * the ramp's cap bounds the step, the bonus pass's grid the effects. The modifier is saved with the creature, so a
 * creature that unloads and comes back keeps its wave's step.
 *
 * <p>World thread only.
 */
public final class WaveDamage {

    /** The key the wave step's modifier sits under on the creature's {@code DamageBonus}. */
    public static final String MODIFIER_KEY = "ziggfreedcommon:wave_damage";

    private WaveDamage() {
    }

    /** The whole percent a damage multiplier raises hits by: 1.25 is 25; nothing below 1. */
    public static int percentOf(double multiplier) {
        double percent = (multiplier - 1.0) * 100.0;
        double top = (WaveRamp.CEILING - 1.0) * 100.0;
        if (!Double.isFinite(percent)) {
            return 0;
        }
        return (int) Math.max(0L, Math.min(Math.round(top), Math.round(percent)));
    }

    /**
     * Put {@code percent} on {@code creature} as its wave step, and bring its outgoing bonus in step at once: nothing
     * for 0. Answers whether it is on. Never throws.
     */
    public static boolean apply(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> creature, int percent) {
        return DamageStep.apply(store, creature, MODIFIER_KEY, percent);
    }
}
