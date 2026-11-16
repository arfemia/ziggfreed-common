package com.ziggfreed.common.proc;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.entity.entities.ProjectileComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The ProjectileMiss moment's producer: a projectile a player launched that leaves the world without having struck a
 * creature (it hit the ground or a wall and was spent, or came to rest and despawned) is their ProjectileMiss, about
 * that projectile's asset id. A projectile's hit on any creature, even one the hit could not hurt, marks it struck
 * ({@link #struck}, heard by {@link ProcSystems.Taken}), so it never misses after. A projectile unloaded with its
 * chunk is no miss.
 *
 * <p>It reads the game's thrown and shot projectiles that carry no chains of their own (a {@code LaunchProjectile}
 * projectile: a thrown spear, a mob's shot); a projectile config whose {@code Interactions} hold a
 * {@code ProjectileMiss} chain runs that chain on a miss instead, so its author answers it there. World thread; the
 * struck set holds projectiles weakly, so one that left is never kept.
 */
final class ProjectileMisses {

    private static final Set<Ref<EntityStore>> STRUCK =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private ProjectileMisses() {
    }

    /** {@code damage} landed on a creature: when a projectile dealt it, that projectile struck. */
    static void struck(@Nonnull Damage damage) {
        if (damage.getSource() instanceof Damage.ProjectileSource source && source.getProjectile() != null) {
            STRUCK.add(source.getProjectile());
        }
    }

    /** Did the projectile {@code ref} strike a creature? */
    static boolean hasStruck(@Nonnull Ref<EntityStore> ref) {
        return STRUCK.contains(ref);
    }

    /**
     * A projectile leaving its store: its shooter's ProjectileMiss when it was removed (not unloaded), struck no
     * creature, and a player launched it. Never throws.
     */
    static void left(@Nonnull Ref<EntityStore> ref, @Nonnull RemoveReason reason, @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        try {
            boolean struck = STRUCK.remove(ref);
            if (reason != RemoveReason.REMOVE || struck || !Procs.listens(ProcTrigger.PROJECTILE_MISS)) {
                return;
            }
            ProjectileComponent projectile = commandBuffer.getComponent(ref, ProjectileComponent.getComponentType());
            UUID creator = projectile == null ? null : projectile.getCreatorUuid();
            Ref<EntityStore> shooter = creator == null ? null : store.getExternalData().getRefFromUUID(creator);
            if (shooter == null || !shooter.isValid()
                    || commandBuffer.getComponent(shooter, PlayerRef.getComponentType()) == null) {
                return;
            }
            Procs.fire(new ProcMoment(ProcTrigger.PROJECTILE_MISS, store, commandBuffer, shooter, null, null, 1.0,
                    null, null, null, null, 0.0, idOf(projectile)));
        } catch (Throwable t) {
            Procs.failed("a projectile's miss", t);
        }
    }

    @Nullable
    private static String idOf(@Nonnull ProjectileComponent projectile) {
        String id = projectile.getProjectileAssetName();
        return id == null || id.isBlank() ? null : id.trim();
    }

    /** Hears every projectile of the game's own kind leaving its store ({@link #left}). */
    public static final class Left extends RefSystem<EntityStore> {

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return ProjectileComponent.getComponentType();
        }

        @Override
        public void onEntityAdded(@Nonnull Ref<EntityStore> ref, @Nonnull AddReason reason,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        }

        @Override
        public void onEntityRemove(@Nonnull Ref<EntityStore> ref, @Nonnull RemoveReason reason,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            left(ref, reason, store, commandBuffer);
        }
    }
}
