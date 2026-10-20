package com.ziggfreed.common.effect.resist;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerInput;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.stats.damage.DamageResistances;
import com.ziggfreed.common.stats.damage.NativeResistance;

/**
 * What a hit lands at once the engine's armor step has taken its resistance (R567): the three things that
 * step cannot express, applied by {@link ResistAfterArmorSystem} and read by a consumer that tells the
 * player what its resistance took ({@link #evaluate}).
 *
 * <ul>
 *   <li><b>Armor penetration</b> ({@link NativeResistance#setPenetration}): a hit that ignores {@code pen} of
 *       the resistance on its own cause lands at {@code amount x (1 - S(1 - pen)) / (1 - S)}, {@code S} the
 *       engine's summed percent on that cause, worn armor's included.</li>
 *   <li><b>A rolled landing</b>: a {@value DamageResistances#FALL} hit taken while the entity rolls the
 *       landing adds its {@value DamageResistances#ROLL_STAT_ID} stat to that sum, held so the sum stays
 *       under the ceiling. The game's own roll mitigation reads the world's movement config, the same for
 *       every player; this is the per-player part.</li>
 *   <li><b>The 1-point minimum</b>, kept only while this library's own resistance is in the sum and the sum
 *       is under a whole hit: a hit that would land for at least a point without our share still lands for
 *       one, so a resistance stops short of immunity (R240) and a small hit never rounds to nothing. A sum of
 *       a whole hit or more (an invulnerability effect) is the game's and is left alone.</li>
 * </ul>
 *
 * <p>Every hit that carries no penetration, takes no roll and meets none of our resistance on its own cause
 * returns at once, so the step costs a cause lookup and one pass over the victim's active effects.
 */
public final class ResistAfterArmor {

    private ResistAfterArmor() {
    }

    /** What the step makes of a hit: the amount it lands at, and the amount it would land at without our share. */
    public record Outcome(double landed, double withoutOurs) {

        @Nonnull
        public static Outcome unchanged(double amount) {
            return new Outcome(amount, amount);
        }

        /** What this library's resistance took off the hit, never below 0. */
        public double blockedByOurs() {
            return Math.max(0.0, withoutOurs - landed);
        }
    }

    // ==================== Pure arithmetic (testable, no engine) ====================

    /**
     * A hit of {@code amount}, after the engine's armor step took {@code sum} (a fraction, our share
     * {@code ours} included) on its own cause: {@code added} joins the sum (a rolled landing's share), and
     * {@code pen} of the positive sum is ignored. The result is floored at a point while {@code ours + added}
     * is above zero and the hit would land for a point without them. A sum of a whole hit or more, or a hit
     * of nothing, comes back unchanged: nothing is left to rescale.
     */
    @Nonnull
    static Outcome outcome(double amount, double sum, double ours, double added, double pen) {
        if (!(amount > 0.0) || !Double.isFinite(amount) || !Double.isFinite(sum) || !(sum < 1.0)) {
            return Outcome.unchanged(amount);
        }
        double p = Double.isFinite(pen) ? Math.max(0.0, Math.min(1.0, pen)) : 0.0;
        double oursShare = Double.isFinite(ours) ? Math.max(0.0, ours) : 0.0;
        double addedShare = Double.isFinite(added) ? Math.max(0.0, added) : 0.0;
        double kept = 1.0 - sum;
        double landed = Math.max(0.0, amount * (1.0 - penetrated(sum + addedShare, p)) / kept);
        double without = Math.max(0.0, amount * (1.0 - penetrated(sum - oursShare, p)) / kept);
        if (oursShare + addedShare > 0.0 && without >= 1.0 && landed < 1.0) {
            landed = 1.0;
        }
        return new Outcome(landed, without);
    }

    /**
     * The share of a rolled landing that joins a {@value DamageResistances#FALL} sum: {@code rollPoints} (whole
     * percent points) as a fraction, held so {@code sum} plus it stays under {@code ceilingPoints}; never
     * below 0.
     */
    static double rollShare(double rollPoints, double ceilingPoints, double sum) {
        if (!(rollPoints > 0.0) || !Double.isFinite(rollPoints) || !Double.isFinite(ceilingPoints)) {
            return 0.0;
        }
        double room = ceilingPoints / 100.0 - Math.max(0.0, sum);
        return room <= 0.0 ? 0.0 : Math.min(rollPoints / 100.0, room);
    }

    /** {@code percent} with {@code pen} of it ignored; a resistance at or below 0 is never penetrated. */
    private static double penetrated(double percent, double pen) {
        return percent > 0.0 ? percent * (1.0 - pen) : percent;
    }

    // ==================== Live (world thread) ====================

    /**
     * What the step makes of {@code damage} on {@code victim} now, at its current amount: the same reading
     * {@link ResistAfterArmorSystem} applies, for a consumer earlier in the Filter group that wants to say
     * what its resistance took. Unchanged for a cancelled hit, a cause that bypasses resistance, or a hit
     * nothing of ours touches. Never throws.
     */
    @Nonnull
    public static Outcome evaluate(@Nonnull ComponentAccessor<EntityStore> accessor, @Nullable Ref<EntityStore> victim,
            @Nonnull Damage damage) {
        double amount = damage.getAmount();
        try {
            if (victim == null || !victim.isValid() || damage.isCancelled() || !(amount > 0.0)) {
                return Outcome.unchanged(amount);
            }
            DamageCause cause = DamageCause.getAssetMap().getAsset(damage.getDamageCauseIndex());
            if (cause == null || cause.getId() == null || cause.doesBypassResistances()) {
                return Outcome.unchanged(amount);
            }
            double pen = NativeResistance.penetrationOf(damage);
            double ours = 0.0;
            if (DamageResistances.canonicalType(cause.getId()) != null) {
                ours = DamageResistReconciler.oursOn(
                        accessor.getComponent(victim, EffectControllerComponent.getComponentType()), cause);
            }
            double rollPoints = DamageResistances.FALL.equalsIgnoreCase(cause.getId())
                    ? rolledLandingPoints(accessor, victim) : 0.0;
            if (!(pen > 0.0) && !(ours > 0.0) && !(rollPoints > 0.0)) {
                return Outcome.unchanged(amount);
            }
            double sum = NativeResistance.of(accessor, victim, cause).exactPercent();
            double added = 0.0;
            if (rollPoints > 0.0) {
                EntityStatMap stats = accessor.getComponent(victim, EntityStatMap.getComponentType());
                double ceiling = stats == null ? DamageResistances.assetCeilingPercent()
                        : DamageResistances.ceilingPercent(accessor, victim, stats);
                added = rollShare(rollPoints, ceiling, sum);
            }
            return outcome(amount, sum, ours, added, pen);
        } catch (Throwable t) {
            return Outcome.unchanged(amount);
        }
    }

    /**
     * The victim's {@value DamageResistances#ROLL_STAT_ID} reading while it rolls the landing, else 0: nothing
     * while the world rule says no, and the movement read only for a reading above zero.
     */
    private static double rolledLandingPoints(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> victim) {
        EntityStatMap stats = accessor.getComponent(victim, EntityStatMap.getComponentType());
        if (stats == null) {
            return 0.0;
        }
        double points = DamageResistances.pointsOf(stats, DamageResistances.ROLL_STAT_ID);
        if (!(points > 0.0) || !DamageResistances.allows(accessor, victim) || !rolling(accessor, victim)) {
            return 0.0;
        }
        return points;
    }

    /**
     * Whether {@code victim} rolls the landing a fall hit is landing with, read as the engine's own fall step
     * reads it. A player's landing reaches that step as a queued movement update which the input pass applies
     * only after the hit, and the step lands on the FIRST queued update that is on the ground while the fall
     * distance is above 0 ({@code DamageSystems.FallDamagePlayers}), so that entry decides; a player with a
     * queue is never read off the movement component, whose state is the tick before. An NPC's landing is its
     * movement component's state ({@code FallDamageNPCs}).
     */
    private static boolean rolling(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> victim) {
        PlayerInput input = accessor.getComponent(victim, PlayerInput.getComponentType());
        List<PlayerInput.InputUpdate> queue = input == null ? null : input.getMovementUpdateQueue();
        if (queue != null && !queue.isEmpty()) {
            Player player = accessor.getComponent(victim, Player.getComponentType());
            if (player == null || !(player.getCurrentFallDistance() > 0)) {
                return false;
            }
            for (PlayerInput.InputUpdate update : queue) {
                if (update instanceof PlayerInput.SetMovementStates set) {
                    MovementStates states = set.movementStates();
                    if (states != null && states.onGround) {
                        return states.rolling;
                    }
                }
            }
            return false;
        }
        MovementStatesComponent movement = accessor.getComponent(victim, MovementStatesComponent.getComponentType());
        MovementStates states = movement == null ? null : movement.getMovementStates();
        return states != null && states.rolling;
    }
}
