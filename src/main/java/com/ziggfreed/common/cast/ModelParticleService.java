package com.ziggfreed.common.cast;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.spatial.SpatialResource;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.ZiggfreedCommonPlugin;

/**
 * Thin, fully-guarded wrappers over the engine {@link ParticleUtil} particle spawns, so a
 * consumer's cast / ability effects share one particle seam instead of each re-deriving the
 * try/catch + player-collection boilerplate.
 *
 * <p>Two shapes, matching the engine calls consumer effects actually make:
 * <ul>
 *   <li>{@link #spawnAt} - position-only spawn to all nearby players
 *       ({@code ParticleUtil.spawnParticleEffect(name, position, accessor)}).</li>
 *   <li>{@link #spawnDirectional} - a rotation-aware spawn. The engine's convenience
 *       overload only handles position, so this collects nearby players itself (mirroring
 *       {@code ParticleUtil}'s own spatial lookup) to reach the rotation-aware overload;
 *       {@code sourceRef} is null so the caster sees its own effect.</li>
 * </ul>
 *
 * <p>Semantics: a {@code null} asset id is a no-op returning {@code false}; any error is
 * caught ({@code Throwable}) and returns {@code false} after a guarded FINE log carrying the
 * throwable message (a caller keeps its own log level on the {@code false} result). Returns
 * {@code true} on a successful spawn.
 *
 * <p><b>World-thread only</b> (reads the player spatial resource + writes packets); the caller
 * guarantees the thread.
 */
public final class ModelParticleService {

    private ModelParticleService() {}

    /**
     * Spawn a named particle system at {@code position} for all nearby players. No-op
     * ({@code false}) for a null asset id or on any error.
     */
    public static boolean spawnAt(@Nonnull Store<EntityStore> store,
                                  @Nullable String particleAsset,
                                  @Nonnull Vector3d position) {
        if (particleAsset == null) return false;
        try {
            ParticleUtil.spawnParticleEffect(particleAsset, position, store);
            return true;
        } catch (Throwable t) {
            fine("particle (" + particleAsset + ") failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * Spawn a rotation-aware particle at {@code position}, broadcast to every player within
     * {@link ParticleUtil#DEFAULT_PARTICLE_DISTANCE}. No-op ({@code false}) for a null asset id
     * or on any error.
     *
     * <p>The nearby-player list is collected into a plain {@link ArrayList} rather than the
     * engine's {@code SpatialResource.getThreadLocalReferenceList()}: that method's fastutil
     * return type is binary-incompatible between the compile-time API and the live runtime, and
     * {@code collect} accepts the standard {@link List} interface.
     */
    public static boolean spawnDirectional(@Nonnull Store<EntityStore> store,
                                           @Nullable String particleAsset,
                                           @Nonnull Vector3d position,
                                           @Nonnull Rotation3f rotation) {
        if (particleAsset == null) return false;
        try {
            SpatialResource<Ref<EntityStore>, EntityStore> playerSpatial =
                    store.getResource(EntityModule.get().getPlayerSpatialResourceType());
            List<Ref<EntityStore>> playerRefs = new ArrayList<>();
            playerSpatial.getSpatialStructure().collect(
                    position, ParticleUtil.DEFAULT_PARTICLE_DISTANCE, playerRefs);
            ParticleUtil.spawnParticleEffect(particleAsset, position, rotation, playerRefs, store);
            return true;
        } catch (Throwable t) {
            fine("directional particle (" + particleAsset + ") failed: " + t.getMessage());
            return false;
        }
    }

    private static void fine(@Nonnull String message) {
        try {
            ZiggfreedCommonPlugin.LOGGER.atFine().log("[ziggfreed-common][particle] " + message);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM: the flogger LOGGER can throw; swallow it.
        }
    }
}
