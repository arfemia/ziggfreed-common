package com.ziggfreed.common.damage.onhit;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.stats.CeilingTags;
import com.ziggfreed.common.stats.condition.BoundGrants;
import com.ziggfreed.common.stats.damage.TypedDamageBonuses;

/**
 * A true share of every hit dealt: the {@value #STAT} stat, whole percent points any attacker carries, a creature as
 * much as a player (R860). {@link DamageDealtSystem} multiplies every hit its carrier deals by
 * {@code 1 + points / 100} after every other step has built it (the percent bonuses the engine sums, a weak spot, a
 * crit), whatever its damage type, a damage-over-time tick and a projectile's included: -10 deals nine tenths of
 * whatever the hit would have been, where -10 on {@code DamageBonus} would only come off the summed percent. The
 * points held under the ceiling the stat's own file names in its {@code ZigCeiling} tag (0 as shipped, so it only ever
 * lowers a hit unless a server raises it), and a hit never goes below nothing.
 */
public final class DamageDealt {

    /** Whole percent points every hit its carrier deals is multiplied by, after every bonus. Base 0. */
    public static final String STAT = "DamageDealt";

    /** The ceiling while the stat's file names none: the multiplier never raises a hit. */
    static final double DEFAULT_CEILING = 0.0;

    private DamageDealt() {
    }

    /**
     * The attacker's points on this hit: {@value #STAT} with the off bound grants on it that hold on this hit
     * ({@link BoundGrants#hitPoints}, R945).
     */
    public static double points(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> attacker,
            @Nonnull Damage damage, @Nonnull EntityStatMap stats) {
        return TypedDamageBonuses.readMax(stats, STAT) + BoundGrants.hitPoints(accessor, attacker, STAT, damage);
    }

    /** The ceiling the stat's file names ({@code ZigCeiling}), else {@link #DEFAULT_CEILING}. */
    static double ceiling() {
        return CeilingTags.ceilingOf(STAT, DEFAULT_CEILING);
    }

    /**
     * What a hit is multiplied by at {@code points} under {@code ceiling}: {@code 1 + min(points, ceiling) / 100}, never
     * below 0; 1 for no points or a number that is not one. Pure.
     */
    public static double factor(double points, double ceiling) {
        if (!Double.isFinite(points) || points == 0.0) {
            return 1.0;
        }
        double held = Double.isFinite(ceiling) ? Math.min(points, ceiling) : points;
        return Math.max(0.0, 1.0 + held / 100.0);
    }
}
