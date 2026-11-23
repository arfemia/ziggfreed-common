package com.ziggfreed.common.effect.control;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.ziggfreed.common.stats.condition.ControlKind;
import com.ziggfreed.common.util.SafeLog;

/**
 * Holding a live creature at a point: the one way a grab or a drive moves a creature (a foe lifted overhead, one driven
 * ahead of a charger). The engine has no carry of a creature by another entity, so the hold is a {@code Set} velocity
 * instruction every tick toward the point: on a walking creature a {@code Set} clears its applied velocities and pushes
 * one entry, its own fall held off while that entry stands, and the role's {@code KnockbackScale} multiplies it first,
 * so a creature the game makes unmovable never moves (a raw write to the component would be overwritten by the
 * creature's own motion). A zero vector pushes nothing, so the hold never sends one. A launch at the end is the
 * caller's own {@code Velocity} instruction.
 *
 * <p>{@link #canMove} is the one rule for whom a grab or a drive may take: a creature, never a player; never one the
 * one landing's gate refuses a Carry ({@link ControlLanding#refuses}: its rules, by the Carry kind's default every boss
 * and elite; its immunity, while the Carry kind is refused by it); one whose role lets it be knocked about; and one no
 * taller than the caller allows (its model's box). It needs nothing wired: a server running CommonLib alone holds the
 * same rule. World thread.
 */
public final class CreatureHold {

    /** World ticks a second: a hold reaches its point within one tick. */
    public static final double TICKS_PER_SECOND = 30.0;

    /** The fastest a hold moves a creature, in blocks a second. */
    public static final double MAX_HOLD_SPEED = 30.0;

    /** The least speed a hold sends, so it never sends the zero vector that would let the creature fall. */
    private static final double MIN_HOLD_SPEED = 0.05;

    private CreatureHold() {
    }

    /**
     * May {@code foe} be lifted or driven: a creature (not a player) on which a Carry lands by its rules (by the family
     * default, no boss or elite), that does not wear the native {@code Immune} effect, has a {@code KnockbackScale}
     * above 0 and stands no taller than {@code maxHeight} blocks. False whenever it cannot tell.
     */
    public static boolean canMove(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> foe,
            double maxHeight) {
        try {
            if (!foe.isValid()) {
                return false;
            }
            NPCEntity npc = accessor.getComponent(foe, NPCEntity.getComponentType());
            if (npc == null || npc.getRole() == null || !(npc.getRole().getKnockbackScale() > 0.0)) {
                return false;
            }
            if (ControlLanding.refuses(accessor, foe, ControlKind.CARRY, false, null)) {
                return false;
            }
            double height = heightOf(accessor, foe);
            return height > 0.0 && height <= maxHeight;
        } catch (Throwable t) {
            SafeLog.fine("CreatureHold: canMove read failed: " + t.getMessage());
            return false;
        }
    }

    /** How tall {@code ref}'s model box is, in blocks; 0 when it has none. */
    public static double heightOf(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref) {
        BoundingBox box = accessor.getComponent(ref, BoundingBox.getComponentType());
        return box != null && box.getBoundingBox() != null ? box.getBoundingBox().height() : 0.0;
    }

    /** Whether {@code ref} stands on the ground (its movement states); false when it cannot tell. */
    public static boolean onGround(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref) {
        try {
            MovementStatesComponent states = accessor.getComponent(ref, MovementStatesComponent.getComponentType());
            return states != null && states.getMovementStates() != null && states.getMovementStates().onGround;
        } catch (Throwable t) {
            SafeLog.fine("CreatureHold: ground read failed: " + t.getMessage());
            return false;
        }
    }

    /** {@code ref}'s position as a copy, or null when it has none. */
    @Nullable
    public static Vector3d positionOf(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref) {
        TransformComponent transform = accessor.getComponent(ref, TransformComponent.getComponentType());
        return transform != null && transform.getPosition() != null ? new Vector3d(transform.getPosition()) : null;
    }

    /**
     * Steer {@code foe} toward {@code point} for one tick: a {@code Set} of the gap times the tick rate, held to
     * {@code maxSpeed}, and never the zero vector. Fail-soft.
     */
    public static void steer(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> foe,
            @Nonnull Vector3d point, double maxSpeed) {
        try {
            Vector3d at = positionOf(accessor, foe);
            Velocity velocity = accessor.getComponent(foe, Velocity.getComponentType());
            if (at == null || velocity == null) {
                return;
            }
            velocity.addInstruction(holdVelocity(at, point, maxSpeed), null, ChangeVelocityType.Set);
        } catch (Throwable t) {
            SafeLog.fine("CreatureHold: steer failed: " + t.getMessage());
        }
    }

    /**
     * The velocity that carries a creature at {@code at} to {@code point} within one tick, no faster than
     * {@code maxSpeed}; a gap too small to send becomes a faint upward nudge, so the hold never lets go. Pure.
     */
    @Nonnull
    public static Vector3d holdVelocity(@Nonnull Vector3d at, @Nonnull Vector3d point, double maxSpeed) {
        Vector3d v = new Vector3d(point).sub(at).mul(TICKS_PER_SECOND);
        double speed = v.length();
        double cap = Math.max(MIN_HOLD_SPEED, maxSpeed);
        if (speed > cap) {
            v.mul(cap / speed);
        } else if (speed < MIN_HOLD_SPEED) {
            v.set(0.0, MIN_HOLD_SPEED, 0.0);
        }
        return v;
    }
}
