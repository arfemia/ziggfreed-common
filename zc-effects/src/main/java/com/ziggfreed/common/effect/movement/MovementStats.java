package com.ziggfreed.common.effect.movement;

import com.ziggfreed.common.effect.grid.RuntimeEffectGrid;

/**
 * The movement stats (R706) and their bucket arithmetic: {@value #MOVE_SPEED} and {@value #KNOCKBACK_RESIST}, each in
 * whole percent points, with their ceiling stats {@value #MOVE_SPEED_MAX} and {@value #KNOCKBACK_RESIST_MAX}. All four
 * ship from zc-entity ({@code Server/Entity/Stats/}); anything that raises a stat raises them (an item's
 * {@code StatModifiers}, an effect's {@code RawStatModifiers}, a pick's grant). {@link MovementReconciler} carries
 * them to the engine as the native {@code ApplicationEffects.HorizontalSpeedMultiplier} and
 * {@code KnockbackMultiplier}, which only an effect can hold.
 *
 * <p>The client multiplies a player's speed by each active effect's {@code HorizontalSpeedMultiplier} (vanilla's own
 * player slows, the charged bow shot and the ground slam's lock, ride that leaf alone), and the engine multiplies the
 * knockback a hit deals by every active effect's {@code KnockbackMultiplier}
 * ({@code DamageEntityInteraction.calculateKnockbackAndArmorModifiers}), so one effect per channel is the whole
 * contribution and it stacks with vanilla's as vanilla's stack with each other.
 */
public final class MovementStats {

    /** Percent bonus to movement speed, whole points. */
    public static final String MOVE_SPEED = "MoveSpeed";

    /** The most {@link #MOVE_SPEED} adds, whole points (its asset's Max is the default ceiling). */
    public static final String MOVE_SPEED_MAX = "MoveSpeedMax";

    /** Percent of knockback shrugged off, whole points. */
    public static final String KNOCKBACK_RESIST = "KnockbackResist";

    /** The most {@link #KNOCKBACK_RESIST} takes off, whole points (its asset's Max is the default ceiling). */
    public static final String KNOCKBACK_RESIST_MAX = "KnockbackResistMax";

    /** The slowest the speed stat makes a player: a tenth of their speed, never a stop. */
    public static final int SPEED_FLOOR = -90;

    /**
     * The highest speed ceiling honoured, whatever a server or an item writes: it bounds the effect catalogue (one
     * asset per whole percent reached) as much as the speed.
     */
    public static final int SPEED_CEILING_LIMIT = 500;

    /** Default speed ceiling, the shipped {@code MoveSpeedMax.json}'s Max, used when the stat cannot be read. */
    public static final double DEFAULT_SPEED_CEILING = 100.0;

    /** Default knockback ceiling, the shipped {@code KnockbackResistMax.json}'s Max, used when the stat cannot be read. */
    public static final double DEFAULT_KNOCKBACK_CEILING = 80.0;

    /** The full knockback resistance: no knockback at all. */
    public static final int FULL_KNOCKBACK = 100;

    private MovementStats() {
    }

    /**
     * The whole-percent speed bucket for a {@code MoveSpeed} total, rounded and held to [{@link #SPEED_FLOOR},
     * the ceiling]; the ceiling itself is held to [0, {@link #SPEED_CEILING_LIMIT}]. 0 for a total that is not a
     * number.
     */
    public static int speedBucket(double points, double ceiling) {
        int top = RuntimeEffectGrid.bucketFor(ceiling, 0, SPEED_CEILING_LIMIT);
        return RuntimeEffectGrid.bucketFor(points, SPEED_FLOOR, top);
    }

    /**
     * The whole-percent knockback bucket for a {@code KnockbackResist} total, rounded and held to [0, the ceiling];
     * the ceiling itself is held to [0, {@link #FULL_KNOCKBACK}], so a negative total never adds knockback.
     */
    public static int knockbackBucket(double points, double ceiling) {
        int top = RuntimeEffectGrid.bucketFor(ceiling, 0, FULL_KNOCKBACK);
        return RuntimeEffectGrid.bucketFor(points, 0, top);
    }

    /** The native speed multiplier a speed bucket carries: 20 is 1.2. */
    public static double speedMultiplier(int bucket) {
        return 1.0 + bucket / 100.0;
    }

    /** The native knockback multiplier a knockback bucket carries: 25 is 0.75. */
    public static double knockbackMultiplier(int bucket) {
        return 1.0 - bucket / 100.0;
    }
}
