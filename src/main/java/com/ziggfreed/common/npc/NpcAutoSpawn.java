package com.ziggfreed.common.npc;

import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;

import org.joml.Vector3d;
import org.joml.Vector3dc;

import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.spawn.ISpawnProvider;
import com.ziggfreed.common.ZiggfreedCommonPlugin;

/**
 * Generic, role-keyed, once-per-world NPC auto-spawn primitive, the config-free lift of
 * kweebec's guide triple ({@code KweebecGuideConfig} + {@code KweebecGuideSpawn} +
 * {@code KweebecGuidePlacementStore}) so a NEW consumer (the upcoming Clash Host NPC)
 * auto-spawns without cloning the same three classes. Nothing kweebec- or MMO-specific
 * lives here: a consumer hands over an {@link AutoSpawnSpec} (the role id, the worlds it
 * may spawn in, an offset from the world spawn point, and a facing yaw) plus the directory
 * its file-backed idempotency marker lives in, and {@link #ensureSpawned} does the rest.
 *
 * <p>The kweebec triple folded into one helper here:
 * <ul>
 *   <li>The CONFIG half ({@code worlds}/offset/yaw) becomes the caller-supplied
 *       {@link AutoSpawnSpec}; the JSON loading is the consumer's policy, not ours (a
 *       consumer may key it off its own config file, the way kweebec does, or hardcode
 *       it).</li>
 *   <li>The SPAWN half ({@code KweebecGuideSpawn.spawnIfAbsent}/{@code place}) becomes
 *       {@link #ensureSpawned}: resolve the world spawn point, offset it, place the role
 *       via {@link NpcSpawnService}, record the UUID, mark the placement.</li>
 *   <li>The PLACEMENT-STORE half ({@code KweebecGuidePlacementStore}) becomes the generic
 *       {@link NpcPlacementStore}, keyed by {@code (world, roleKey)} (not world alone) so
 *       one store can back several distinct auto-spawned NPCs in the same world.</li>
 * </ul>
 *
 * <p><b>World-thread only.</b> {@link #ensureSpawned} reads the world spawn point / player
 * archetype and calls {@code NPCPlugin.spawnEntity}, which are valid only inside a
 * {@code world.execute(...)} task; the CALLER guarantees the world thread (e.g. by wrapping
 * the call in {@code world.execute} on a {@code PlayerReadyEvent}, the way kweebec does).
 * Every path is try-guarded so a missing asset / bad ref / IO error degrades to a no-op
 * (logged), never a throw into the caller.
 *
 * <p>Idempotency is the persisted {@link NpcPlacementStore}: a spawned NPC persists in the
 * world's entity store, so the marker MUST persist too or a fresh boot stacks another NPC
 * beside the saved one. The {@code (world, roleKey)} pair is marked AFTER a successful
 * place, so a failed spawn simply retries on the next call. {@code world.execute} tasks run
 * serialized on the world thread, so the check-then-mark needs no separate atomic claim.
 */
public final class NpcAutoSpawn {

    private NpcAutoSpawn() {
    }

    /**
     * A self-contained auto-spawn request, the generic stand-in for the kweebec guide's
     * config fields.
     *
     * @param roleKey   the idempotency key for this auto-spawn (the placement marker is
     *                  keyed by {@code (world, roleKey)}); distinct consumers in one world
     *                  use distinct keys so they never collide. Typically equal to
     *                  {@code roleAsset}, but kept separate so a consumer can rename the
     *                  asset without orphaning its marker.
     * @param roleAsset the registered NPC role id to spawn (the role asset owns the NPC's
     *                  appearance / behavior / press-F interaction)
     * @param worlds    the worlds this NPC may auto-spawn in (a single {@code "*"} = every
     *                  world; an empty set = nowhere). World names match
     *                  {@link World#getName()}
     * @param offsetX   X offset added to the resolved world spawn point
     * @param offsetY   Y offset added to the resolved world spawn point
     * @param offsetZ   Z offset added to the resolved world spawn point
     * @param yaw       the facing yaw for the placed NPC
     */
    public record AutoSpawnSpec(
            @Nonnull String roleKey,
            @Nonnull String roleAsset,
            @Nonnull Set<String> worlds,
            double offsetX,
            double offsetY,
            double offsetZ,
            float yaw) {

        /** Whether this NPC may auto-spawn in {@code worldName} per {@link #worlds()}. */
        public boolean shouldSpawnInWorld(@Nonnull String worldName) {
            return worlds.contains("*") || worlds.contains(worldName);
        }
    }

    /**
     * Loads (and caches) the persisted placement store under {@code placementStoreDir}, so
     * a subsequent {@link #ensureSpawned} sees prior-session markers immediately. The
     * generic mirror of {@code KweebecGuidePlacementStore.load}. Idempotent (the store is
     * cached per directory) and guarded; a null-ish / unreadable dir degrades the store to
     * in-memory-only rather than throwing.
     *
     * <p>Calling this is OPTIONAL: {@link #ensureSpawned} lazily loads the same store on
     * first use. A consumer calls it at setup only to surface a load failure early.
     */
    public static void init(@Nonnull Path placementStoreDir) {
        try {
            NpcPlacementStore.forDir(placementStoreDir);
        } catch (Throwable t) {
            warn("[NpcAutoSpawn] init failed for " + placementStoreDir + ": " + t.getMessage());
        }
    }

    /**
     * Spawns {@code spec}'s role NPC once per {@code (world, spec.roleKey())} if it has no
     * recorded placement under {@code placementStoreDir}, records the placement, and is
     * idempotent across restarts. A no-op when the world is not in {@code spec.worlds()},
     * when a placement already exists, when the role is not registered, or when the world
     * spawn point cannot be resolved. The generic mirror of
     * {@code KweebecGuideSpawn.spawnIfAbsent} + {@code place}.
     *
     * <p><b>World-thread only</b> (the caller guarantees it; see the class doc). Fully
     * try-guarded - any failure logs and returns without throwing.
     */
    public static void ensureSpawned(@Nonnull World world, @Nonnull AutoSpawnSpec spec,
            @Nonnull Path placementStoreDir) {
        try {
            String worldName = world.getName();
            if (worldName == null || !spec.shouldSpawnInWorld(worldName)) {
                return;
            }
            if (spec.roleAsset().isBlank()) {
                return;
            }

            NpcPlacementStore placements = NpcPlacementStore.forDir(placementStoreDir);
            // Persistent once-per-(world, roleKey) gate. Serialized on the world thread, so
            // the check-then-mark below needs no separate atomic claim.
            if (placements.hasSpawned(worldName, spec.roleKey())) {
                return;
            }

            Vector3dc base = resolveWorldSpawnPosition(world);
            if (base == null) {
                fine("[NpcAutoSpawn] no world spawn point for '" + worldName + "', skipping role '"
                        + spec.roleAsset() + "'");
                return;
            }
            Vector3d pos = new Vector3d(
                    base.x() + spec.offsetX(), base.y() + spec.offsetY(), base.z() + spec.offsetZ());

            if (place(world, spec, worldName, pos, placements)) {
                placements.markSpawned(worldName, spec.roleKey());
                info("[NpcAutoSpawn] spawned role '" + spec.roleAsset() + "' in world '" + worldName + "'.");
            }
        } catch (Throwable t) {
            warn("[NpcAutoSpawn] ensureSpawned failed: " + t.getMessage());
        }
    }

    /**
     * Places the role and records the placed NPC's UUID (mirrors
     * {@code KweebecGuideSpawn.place}). The spawn + UUID read all route through the
     * already-lifted {@link NpcSpawnService}; the post-spawn callback runs on the world
     * thread with the placed entity.
     */
    private static boolean place(@Nonnull World world, @Nonnull AutoSpawnSpec spec,
            @Nonnull String worldName, @Nonnull Vector3dc pos, @Nonnull NpcPlacementStore placements) {
        // Resolve a Store from the world for spawnRole. world.execute runs on the world
        // thread, where this Store is valid.
        var store = world.getEntityStore().getStore();
        return NpcSpawnService.spawnRole(world, store, spec.roleAsset(), pos, spec.yaw(), (npc, npcRef, st) -> {
            try {
                UUIDComponent uc = st.getComponent(npcRef, UUIDComponent.getComponentType());
                if (uc != null && uc.getUuid() != null) {
                    placements.recordUuid(worldName, spec.roleKey(), uc.getUuid());
                }
            } catch (Throwable ignored) {
                // best-effort UUID record (only used by a consumer's debug reposition)
            }
        });
    }

    /**
     * The world spawn point (the caller applies any offset), resolved from the world's own
     * spawn provider with a synthetic UUID (the auto-spawn is world-anchored, not tied to
     * any one player). Returns null when no spawn provider resolves a point. The
     * player-less counterpart of {@link NpcSpawnService#resolveSpawnPosition} - we cannot
     * fall back to a player position here because no player ref is supplied. World-thread
     * only.
     */
    private static Vector3dc resolveWorldSpawnPosition(@Nonnull World world) {
        try {
            ISpawnProvider provider = world.getWorldConfig().getSpawnProvider();
            if (provider != null) {
                Transform sp = provider.getSpawnPoint(world, UUID.randomUUID());
                if (sp != null && sp.getPosition() != null) {
                    return sp.getPosition();
                }
            }
        } catch (Throwable t) {
            fine("[NpcAutoSpawn] spawn provider failed for '" + world.getName() + "': " + t.getMessage());
        }
        return null;
    }

    private static void info(@Nonnull String msg) {
        try {
            ZiggfreedCommonPlugin.LOGGER.atInfo().log("%s", msg);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM; swallow.
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
