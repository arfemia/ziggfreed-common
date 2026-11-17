package com.ziggfreed.common.proc;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.server.core.entity.InteractionChain;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.ProjectileComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
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
 * {@code ProjectileMiss} chain runs that chain on a miss instead, so its author answers it there.
 *
 * <p><b>What it was thrown from.</b> The game's projectile names no item, and a throw spends the item it throws (the
 * last of a stack is gone before the projectile lands in the world), so the item is read where the throw began: each
 * chain a player starts holding an item is noted ({@link #chainStarted}, the chain's own held stack, its durability and
 * metadata included), and a projectile that player launches while that chain still runs, or just after it ends, takes
 * one of that stack as the item it flew from. A miss hands it on as the moment's {@code thrown}, which a proc's
 * {@code ReturnThrown} gives back. Noted only while a live proc asks for ProjectileMiss. World thread; the struck set
 * and the thrown-from notes hold projectiles weakly, so one that left is never kept.
 */
final class ProjectileMisses {

    /** How long after its chain ended a projectile still takes that chain's held item as what it flew from. */
    static final long GRACE_NANOS = 2_000_000_000L;

    private record Held(@Nonnull InteractionChain chain, @Nonnull ItemStack item, long startedNanos) {
    }

    private static final Set<Ref<EntityStore>> STRUCK =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private static final Map<Ref<EntityStore>, ItemStack> THROWN_FROM =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final Map<Ref<EntityStore>, Held> LAST_HELD = new ConcurrentHashMap<>();

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
     * The player {@code holder} started {@code chain} (its {@code context}): note the stack they held as it began, the
     * item a projectile it launches flew from. A chain started empty-handed leaves the last note alone.
     */
    static void chainStarted(@Nonnull Ref<EntityStore> holder, @Nonnull InteractionChain chain,
            @Nullable InteractionContext context, long nanos) {
        ItemStack held = context == null ? null : context.getHeldItem();
        if (held == null || held.isEmpty()) {
            return;
        }
        LAST_HELD.put(holder, new Held(chain, held, nanos));
    }

    /**
     * The item {@code shooter}'s projectile spawned now flew from, quantity 1: the stack held as their latest chain
     * began, while that chain runs or within {@link #GRACE_NANOS} of its start once it ended; else null.
     */
    @Nullable
    static ItemStack thrownBy(@Nonnull Ref<EntityStore> shooter, long nanos) {
        Held held = LAST_HELD.get(shooter);
        if (held == null) {
            return null;
        }
        if (held.chain().getServerState() != InteractionState.NotFinished
                && nanos - held.startedNanos() > GRACE_NANOS) {
            return null;
        }
        return held.item().withQuantity(1);
    }

    /** Forget a player who left their store. */
    static void forget(@Nonnull Ref<EntityStore> ref) {
        LAST_HELD.remove(ref);
    }

    /** A projectile entering its store: note the item it flew from when a player launched it while a proc asks. */
    static void spawned(@Nonnull Ref<EntityStore> ref, @Nonnull AddReason reason, @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        try {
            if (reason != AddReason.SPAWN || !Procs.listens(ProcTrigger.PROJECTILE_MISS)) {
                return;
            }
            Ref<EntityStore> shooter = shooterOf(ref, store, commandBuffer);
            ItemStack thrown = shooter == null ? null : thrownBy(shooter, System.nanoTime());
            if (thrown != null) {
                THROWN_FROM.put(ref, thrown);
            }
        } catch (Throwable t) {
            Procs.failed("a projectile's launch", t);
        }
    }

    /**
     * A projectile leaving its store: its shooter's ProjectileMiss when it was removed (not unloaded), struck no
     * creature, and a player launched it. Never throws.
     */
    static void left(@Nonnull Ref<EntityStore> ref, @Nonnull RemoveReason reason, @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        try {
            boolean struck = STRUCK.remove(ref);
            ItemStack thrown = THROWN_FROM.remove(ref);
            if (reason != RemoveReason.REMOVE || struck || !Procs.listens(ProcTrigger.PROJECTILE_MISS)) {
                return;
            }
            ProjectileComponent projectile = commandBuffer.getComponent(ref, ProjectileComponent.getComponentType());
            Ref<EntityStore> shooter = shooterOf(ref, store, commandBuffer);
            if (projectile == null || shooter == null) {
                return;
            }
            Procs.fire(new ProcMoment(ProcTrigger.PROJECTILE_MISS, store, commandBuffer, shooter, null, null, 1.0,
                    null, null, null, null, 0.0, idOf(projectile), null, thrown));
        } catch (Throwable t) {
            Procs.failed("a projectile's miss", t);
        }
    }

    /** The live player who launched the projectile {@code ref}, or null. */
    @Nullable
    private static Ref<EntityStore> shooterOf(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        ProjectileComponent projectile = commandBuffer.getComponent(ref, ProjectileComponent.getComponentType());
        UUID creator = projectile == null ? null : projectile.getCreatorUuid();
        Ref<EntityStore> shooter = creator == null ? null : store.getExternalData().getRefFromUUID(creator);
        if (shooter == null || !shooter.isValid()
                || commandBuffer.getComponent(shooter, PlayerRef.getComponentType()) == null) {
            return null;
        }
        return shooter;
    }

    @Nullable
    private static String idOf(@Nonnull ProjectileComponent projectile) {
        String id = projectile.getProjectileAssetName();
        return id == null || id.isBlank() ? null : id.trim();
    }

    /** Hears every projectile of the game's own kind entering ({@link #spawned}) and leaving ({@link #left}). */
    public static final class Left extends RefSystem<EntityStore> {

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return ProjectileComponent.getComponentType();
        }

        @Override
        public void onEntityAdded(@Nonnull Ref<EntityStore> ref, @Nonnull AddReason reason,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            spawned(ref, reason, store, commandBuffer);
        }

        @Override
        public void onEntityRemove(@Nonnull Ref<EntityStore> ref, @Nonnull RemoveReason reason,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            left(ref, reason, store, commandBuffer);
        }
    }
}
