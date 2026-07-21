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
 * <p>Three shapes, matching the engine calls consumer effects actually make:
 * <ul>
 *   <li>{@link #spawnAt(Store, String, Vector3d)} - position-only spawn to all nearby players,
 *       UNCAPPED duration ({@code ParticleUtil.spawnParticleEffect(name, position, accessor)}) -
 *       the particle system plays out its own natural spawner budget (a finite one-shot burst
 *       ends on its own; an authored unbounded spawner runs forever).</li>
 *   <li>{@link #spawnAt(Store, String, Vector3d, float)} - the same spawn, capped to
 *       {@code maxDurationSeconds} of client playback via the native
 *       {@code SpawnParticleSystem.maxDuration} field - the SAME field the first-party
 *       {@code PlayVfxEffect} trigger-volume effect authors through its own {@code Duration}
 *       leaf. Use this for a ONE-SHOT moment fired at a bare position (no entity/effect
 *       lifecycle to hang a despawn off): some particle systems are authored with an
 *       unbounded spawner (a negative {@code TotalParticles}, e.g. {@code Block_Gem_Sparks} /
 *       {@code Effect_Crown_Gold}) for a PERSISTENT per-entity VFX use case, and firing one of
 *       those at a raw world position with no cap leaks it there forever - a positive cap
 *       force-stops it instead. A genuinely one-shot burst asset finishes well inside a modest
 *       cap on its own, so the cap is invisible for those.</li>
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
     * Spawn a named particle system at {@code position} for all nearby players, UNCAPPED (the
     * particle system's own natural spawner budget governs how long it plays). No-op
     * ({@code false}) for a null asset id or on any error. Equivalent to
     * {@link #spawnAt(Store, String, Vector3d, float) spawnAt(store, particleAsset, position, 0f)}.
     */
    public static boolean spawnAt(@Nonnull Store<EntityStore> store,
                                  @Nullable String particleAsset,
                                  @Nonnull Vector3d position) {
        return spawnAt(store, particleAsset, position, 0f);
    }

    /**
     * Spawn a named particle system at {@code position} for all nearby players, capped to
     * {@code maxDurationSeconds} of client playback ({@code <= 0} = uncapped, matching the
     * 3-arg overload's behavior). See the class javadoc for when a positive cap is needed. No-op
     * ({@code false}) for a null asset id or on any error.
     */
    public static boolean spawnAt(@Nonnull Store<EntityStore> store,
                                  @Nullable String particleAsset,
                                  @Nonnull Vector3d position,
                                  float maxDurationSeconds) {
        if (particleAsset == null) return false;
        try {
            ParticleUtil.spawnParticleEffect(particleAsset, position, 0f, 0f, 0f, 1f, maxDurationSeconds, store);
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
