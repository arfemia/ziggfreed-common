package com.ziggfreed.common.npc;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3dc;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.function.consumer.TriConsumer;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.ziggfreed.common.ZiggfreedCommonPlugin;

/**
 * The generic "spawn an NPC role at a position" primitive, lifted config-free from
 * the MMO Skill Tree mod's {@code MmoNpcSpawnService.spawnRole}. {@link #spawnRole}
 * resolves a registered NPC role by name and places it; {@link #resolveSpawnPosition}
 * resolves a world spawn point (falling back to a player's position); {@link #despawn}
 * removes a spawned NPC by UUID.
 *
 * <p>Nothing mod-specific lives here: a consumer (a minigame guide, a hub NPC, a
 * quest giver) supplies the role id + position and, optionally, a {@code postSpawn}
 * callback to record the spawned entity (the MMO records placements; a per-round
 * minigame NPC is fire-and-forget). The NPC's appearance, stationary behavior, and
 * its press-F interaction all live in the role asset; this service only places it.
 *
 * <p><b>World-thread only</b> (inside {@code world.execute(...)}):
 * {@code spawnEntity}/{@code removeEntity} are valid only outside the ECS processing
 * window, which is exactly what the world task queue provides. Every method is
 * try-guarded so a throw never breaks player-ready / chunk load.
 */
public final class NpcSpawnService {

    private NpcSpawnService() {
    }

    /**
     * Places {@code role} at {@code position} facing {@code yaw} (a fire-and-forget
     * spawn with no post-spawn recorder). Returns false (logged) when the role is not
     * registered or the spawn fails.
     */
    public static boolean spawnRole(@Nonnull World world, @Nonnull Store<EntityStore> store, @Nonnull String role,
            @Nonnull Vector3dc position, float yaw) {
        return spawnRole(world, store, role, position, yaw, null);
    }

    /**
     * Places {@code role} at {@code position} facing {@code yaw}, invoking
     * {@code postSpawn} on the world thread with the spawned entity (e.g. to read its
     * {@link UUIDComponent} and record the placement). Returns false (logged) when the
     * role is not registered or the spawn fails.
     *
     * @param postSpawn invoked once with {@code (npcEntity, npcRef, store)} after the
     *                  entity is created, or {@code null} for a fire-and-forget spawn
     */
    public static boolean spawnRole(@Nonnull World world, @Nonnull Store<EntityStore> store, @Nonnull String role,
            @Nonnull Vector3dc position, float yaw,
            @Nullable TriConsumer<NPCEntity, Ref<EntityStore>, Store<EntityStore>> postSpawn) {
        if (role.isBlank()) {
            return false;
        }
        NPCPlugin npc = NPCPlugin.get();
        if (npc == null || !npc.hasRoleName(role)) {
            warn("[NpcSpawn] NPC role '" + role + "' not registered - skipping spawn");
            return false;
        }
        int idx = npc.getIndex(role);
        if (idx < 0) {
            return false;
        }

        Rotation3f rotation = new Rotation3f(0.0f, yaw, 0.0f);
        try {
            var result = npc.spawnEntity(store, idx, position, rotation, null, postSpawn);
            if (result == null) {
                warn("[NpcSpawn] spawnEntity returned null for role " + role);
                return false;
            }
            return true;
        } catch (Exception e) {
            warn("[NpcSpawn] failed to spawn role '" + role + "': " + e.getMessage());
            return false;
        }
    }

    /**
     * The world spawn point (the caller applies any offset), falling back to
     * {@code playerRef}'s current position if no spawn provider resolves. Fully
     * generic. World-thread only.
     */
    @Nullable
    public static Vector3dc resolveSpawnPosition(@Nonnull World world, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> playerRef) {
        try {
            var provider = world.getWorldConfig().getSpawnProvider();
            if (provider != null) {
                UUIDComponent uc = store.getComponent(playerRef, UUIDComponent.getComponentType());
                UUID uuid = uc != null ? uc.getUuid() : UUID.randomUUID();
                Transform sp = provider.getSpawnPoint(world, uuid);
                if (sp != null && sp.getPosition() != null) {
                    return sp.getPosition();
                }
            }
        } catch (Exception e) {
            fine("[NpcSpawn] spawn provider failed, falling back to player position: " + e.getMessage());
        }
        try {
            TransformComponent tc = store.getComponent(playerRef, TransformComponent.getComponentType());
            if (tc != null) {
                return tc.getPosition();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Despawns the entity with {@code uuid} if it's resident. World thread only. */
    public static boolean despawn(@Nonnull Store<EntityStore> store, @Nonnull UUID uuid) {
        try {
            EntityStore es = store.getExternalData();
            Ref<EntityStore> ref = es.getRefFromUUID(uuid);
            if (ref == null || !ref.isValid()) {
                return false;
            }
            store.removeEntity(ref, RemoveReason.REMOVE);
            return true;
        } catch (Exception e) {
            warn("[NpcSpawn] despawn failed: " + e.getMessage());
            return false;
        }
    }

    private static void warn(@Nonnull String msg) {
        try {
            ZiggfreedCommonPlugin.LOGGER.atWarning().log("%s", msg);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM; swallow.
        }
    }

    private static void fine(@Nonnull String msg) {
        try {
            ZiggfreedCommonPlugin.LOGGER.atFine().log("%s", msg);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM; swallow.
        }
    }
}
