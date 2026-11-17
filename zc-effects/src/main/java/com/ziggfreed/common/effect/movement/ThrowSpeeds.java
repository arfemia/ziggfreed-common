package com.ziggfreed.common.effect.movement;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.entities.ProjectileComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.stats.StatIndexCache;
import com.ziggfreed.common.util.SafeLog;

/**
 * The one applier of the {@value #THROW_SPEED} stat (whole percent points, zc-entity's
 * {@code Server/Entity/Stats/ThrowSpeed.json}): a thrown projectile, the game's kind that a {@code LaunchProjectile}
 * step launches at its asset's {@code MuzzleVelocity} (a thrown spear, a thrown potion, a creature's thrown shot),
 * leaves its thrower that much faster, so it also flies further; held to [{@value #FLOOR}, {@value #CEILING}], and the
 * projectile's own terminal speed still holds. Anything that raises a stat raises it (an item's
 * {@code StatModifiers}, an effect's {@code RawStatModifiers}, a pick's grant, bound {@code While} a weapon is held);
 * a projectile launched through a projectile config (an arrow, a bomb) has a launch force of its own and is left
 * alone.
 *
 * <p>The game sets a thrown projectile's speed as it spawns, inside its physics, where nothing can read it, and the
 * first physics step writes it onto the projectile's {@code Velocity} component, which every later step reads back
 * first. So {@link Launched} notes each one a thrower with the stat spawns, and {@link Speeding} multiplies that
 * component's speed once the first step has written it (one tick at the old speed, a thirtieth of a second). World
 * thread; the note holds projectiles weakly.
 */
public final class ThrowSpeeds {

    /** Percent bonus to a thrown projectile's launch speed, whole points. */
    public static final String THROW_SPEED = "ThrowSpeed";

    /** The slowest the stat throws: a tenth of the speed, never a drop at the feet. */
    public static final int FLOOR = -90;

    /** The fastest the stat throws: four times the speed. */
    public static final int CEILING = 300;

    private static final Map<Ref<EntityStore>, Double> PENDING =
            Collections.synchronizedMap(new WeakHashMap<>());

    private ThrowSpeeds() {
    }

    /** The launch speed multiplier a {@value #THROW_SPEED} total gives, held to the floor and ceiling. Pure. */
    public static double factor(double points) {
        if (!Double.isFinite(points)) {
            return 1.0;
        }
        double held = Math.max(FLOOR, Math.min(CEILING, points));
        return 1.0 + held / 100.0;
    }

    /** {@code shooter}'s {@value #THROW_SPEED} total, 0 when it carries none. */
    static double points(@Nullable EntityStatMap stats) {
        if (stats == null) {
            return 0.0;
        }
        int index = StatIndexCache.resolve(THROW_SPEED);
        if (index == StatIndexCache.UNRESOLVED) {
            return 0.0;
        }
        EntityStatValue value = stats.get(index);
        return value == null ? 0.0 : value.getMax();
    }

    /** Notes each thrown projectile whose thrower carries the stat as it spawns, and forgets one that leaves. */
    public static final class Launched extends RefSystem<EntityStore> {

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return ProjectileComponent.getComponentType();
        }

        @Override
        public void onEntityAdded(@Nonnull Ref<EntityStore> ref, @Nonnull AddReason reason,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            if (reason != AddReason.SPAWN) {
                return;
            }
            try {
                ProjectileComponent projectile = commandBuffer.getComponent(ref, ProjectileComponent.getComponentType());
                if (projectile == null || projectile.getCreatorUuid() == null) {
                    return;
                }
                Ref<EntityStore> thrower = store.getExternalData().getRefFromUUID(projectile.getCreatorUuid());
                if (thrower == null || !thrower.isValid()) {
                    return;
                }
                double factor = factor(points(commandBuffer.getComponent(thrower, EntityStatMap.getComponentType())));
                if (Math.abs(factor - 1.0) >= 0.005) {
                    PENDING.put(ref, factor);
                }
            } catch (Throwable t) {
                SafeLog.warn("[throw speed] could not read a thrown projectile's thrower", t);
            }
        }

        @Override
        public void onEntityRemove(@Nonnull Ref<EntityStore> ref, @Nonnull RemoveReason reason,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            PENDING.remove(ref);
        }
    }

    /** Multiplies a noted projectile's speed once its first physics step has put the launch speed on it. */
    public static final class Speeding extends EntityTickingSystem<EntityStore> {

        @Nonnull
        private final Query<EntityStore> query = Query.and(ProjectileComponent.getComponentType(),
                Velocity.getComponentType());

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return query;
        }

        @Override
        public boolean isParallel(int archetypeChunkSize, int taskCount) {
            return false;
        }

        @Override
        public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
            if (PENDING.isEmpty()) {
                return;
            }
            super.tick(dt, systemIndex, store);
        }

        @Override
        public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> chunk,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            Ref<EntityStore> ref = chunk.getReferenceTo(index);
            Double factor = PENDING.get(ref);
            if (factor == null) {
                return;
            }
            try {
                Velocity velocity = chunk.getComponent(index, Velocity.getComponentType());
                if (velocity == null) {
                    PENDING.remove(ref);
                    return;
                }
                Vector3d now = velocity.getVelocity();
                if (now == null || now.lengthSquared() < 1.0e-6) {
                    return;
                }
                velocity.set(new Vector3d(now).mul(factor));
                PENDING.remove(ref);
            } catch (Throwable t) {
                PENDING.remove(ref);
                SafeLog.warn("[throw speed] could not speed a thrown projectile", t);
            }
        }
    }
}
