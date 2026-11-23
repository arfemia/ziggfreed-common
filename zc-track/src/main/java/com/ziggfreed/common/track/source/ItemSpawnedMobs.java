package com.ziggfreed.common.track.source;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.interactions.SpawnNPCInteraction;
import com.ziggfreed.common.util.SafeLog;

/**
 * The creatures an item stood up (a spawn egg: an item whose root interaction runs the engine's {@code SpawnNPC}
 * among its own interactions, as the game's eggs author it inline; a {@code SpawnNPC} nested deeper in a chain is not
 * seen), which a track source's {@code SkipSpawnerMobs} pays nothing for, as it pays nothing for a spawn marker's or
 * a spawn beacon's. The engine marks no such creature (its spawn writes only the creature's own components), so the
 * library watches the two moments it can see: a chain on a player whose root runs {@code SpawnNPC} arms the spot for
 * a moment ({@link #armed}, from zc-track's activity watch), and a creature the engine spawns within {@link #REACH}
 * of an armed spot in the next {@link #ARM_MS} is marked by its id ({@link Born}). A world spawn landing beside a
 * player who used an egg that same moment is marked too; nothing else is.
 *
 * <p>Kept in memory only, each mark for {@link #KEEP_MS}: a creature an egg stood up before a restart pays again
 * after it. World thread: a world's arms are read and written only on its own thread.
 */
public final class ItemSpawnedMobs {

    /** How long after a player starts a spawning chain its creature may stand up. */
    static final long ARM_MS = 2_000L;

    /** How far from the player using it an item's creature may stand up, in blocks. */
    static final double REACH = 24.0;

    /** How long a creature stays marked. */
    static final long KEEP_MS = 6L * 60L * 60L * 1000L;

    /** At most this many marks are kept; past it the oldest are swept first. */
    static final int MAX_MARKS = 50_000;

    /** One armed spot: where a spawning chain started, and when. */
    private record Arm(double x, double y, double z, long atMs) {
    }

    private static final Map<World, Deque<Arm>> ARMED = new ConcurrentHashMap<>();

    private static final Map<UUID, Long> MARKED = new ConcurrentHashMap<>();

    private ItemSpawnedMobs() {
    }

    /** Did an item stand up the creature {@code uuid} (a mark not yet worn out)? */
    public static boolean isMarked(@Nullable UUID uuid) {
        if (uuid == null) {
            return false;
        }
        Long at = MARKED.get(uuid);
        return at != null && System.currentTimeMillis() - at < KEEP_MS;
    }

    /**
     * Does the root interaction {@code rootId} run the engine's {@code SpawnNPC} among its own interactions (an egg's
     * chain)? False for an id no root answers. Asset reads only, any thread.
     */
    public static boolean spawnsCreature(@Nullable String rootId) {
        if (rootId == null || rootId.isBlank()) {
            return false;
        }
        try {
            RootInteraction root = RootInteraction.getAssetMap().getAsset(rootId);
            String[] ids = root == null ? null : root.getInteractionIds();
            if (ids == null) {
                return false;
            }
            for (String id : ids) {
                Interaction interaction = id == null ? null : Interaction.getAssetMap().getAsset(id);
                if (interaction instanceof SpawnNPCInteraction) {
                    return true;
                }
            }
        } catch (Throwable t) {
            // The interaction stores are not loaded here, so no chain is known to spawn.
        }
        return false;
    }

    /** A player at ({@code x}, {@code y}, {@code z}) in {@code world} started a spawning chain at {@code nowMs}. */
    public static void armed(@Nonnull World world, double x, double y, double z, long nowMs) {
        Deque<Arm> arms = ARMED.computeIfAbsent(world, w -> new ArrayDeque<>());
        synchronized (arms) {
            prune(arms, nowMs);
            arms.addLast(new Arm(x, y, z, nowMs));
        }
    }

    /** Is ({@code x}, {@code y}, {@code z}) in {@code world} inside a spot armed in the last {@link #ARM_MS}? */
    static boolean armedNear(@Nonnull World world, double x, double y, double z, long nowMs) {
        Deque<Arm> arms = ARMED.get(world);
        if (arms == null) {
            return false;
        }
        synchronized (arms) {
            prune(arms, nowMs);
            if (arms.isEmpty()) {
                // Spent: the next creature spawned in this world asks nothing until an egg arms it again.
                ARMED.remove(world, arms);
                return false;
            }
            for (Arm arm : arms) {
                if (near(arm.x(), arm.y(), arm.z(), x, y, z)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Within {@link #REACH} of each other? Pure. */
    static boolean near(double x0, double y0, double z0, double x1, double y1, double z1) {
        double dx = x1 - x0;
        double dy = y1 - y0;
        double dz = z1 - z0;
        return dx * dx + dy * dy + dz * dz <= REACH * REACH;
    }

    private static void prune(@Nonnull Deque<Arm> arms, long nowMs) {
        while (!arms.isEmpty() && nowMs - arms.peekFirst().atMs() > ARM_MS) {
            arms.removeFirst();
        }
    }

    /** Mark {@code uuid} as stood up by an item at {@code nowMs}, sweeping worn-out marks when there are many. */
    static void mark(@Nonnull UUID uuid, long nowMs) {
        if (MARKED.size() >= MAX_MARKS) {
            Iterator<Map.Entry<UUID, Long>> it = MARKED.entrySet().iterator();
            while (it.hasNext()) {
                if (nowMs - it.next().getValue() >= KEEP_MS) {
                    it.remove();
                }
            }
        }
        MARKED.put(uuid, nowMs);
    }

    /** Forget a world's arms (its removal). */
    public static void forgetWorld(@Nullable World world) {
        if (world != null) {
            ARMED.remove(world);
        }
    }

    /**
     * Marks a creature the engine spawns inside an armed spot. Runs inside the store's add, reading the creature's own
     * place and id only. Never throws.
     */
    public static final class Born extends RefSystem<EntityStore> {

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return NPCEntity.getComponentType();
        }

        @Override
        public void onEntityAdded(@Nonnull Ref<EntityStore> ref, @Nonnull AddReason reason,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            if (reason != AddReason.SPAWN || ARMED.isEmpty()) {
                return;
            }
            try {
                World world = store.getExternalData().getWorld();
                if (world == null || !ARMED.containsKey(world)) {
                    return;
                }
                TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
                UUIDComponent id = store.getComponent(ref, UUIDComponent.getComponentType());
                Vector3d at = transform == null ? null : transform.getPosition();
                long now = System.currentTimeMillis();
                if (at != null && id != null && id.getUuid() != null && armedNear(world, at.x, at.y, at.z, now)) {
                    mark(id.getUuid(), now);
                }
            } catch (Throwable t) {
                SafeLog.fine("[track] could not tell whether an item stood a creature up: " + t.getMessage());
            }
        }

        @Override
        public void onEntityRemove(@Nonnull Ref<EntityStore> ref, @Nonnull RemoveReason reason,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        }
    }
}
