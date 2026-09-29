package com.ziggfreed.common.cast;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import org.joml.Vector3fc;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.spatial.SpatialResource;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.Color;
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.EntityPart;
import com.hypixel.hytale.protocol.ModelParticle;
import com.hypixel.hytale.protocol.packets.entities.SpawnModelParticles;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.CommonLog;
import com.ziggfreed.common.entity.EntityViewers;

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
 *   <li>{@link #spawnAt(Store, String, Vector3d, float, float, float, float, Color, float)} - the
 *       FULL-ARITY world-positioned spawn: rotation, scale, an optional tint and the playback cap
 *       together, through the one engine overload that carries both a {@code Color} and a
 *       {@code maxDuration} ({@code SpawnParticleSystem} carries both fields), so a tinted burst
 *       keeps its leak guard. A null colour plays the system's authored colours.</li>
 *   <li>{@link #spawnOn} - an ENTITY-ATTACHED spawn ({@code SpawnModelParticles}, the packet the
 *       engine's own NPC action and spawn effect send): the system rides the entity, or one named
 *       node of its model, and is delivered ONLY to the players whose tracker currently shows that
 *       entity ({@link EntityViewers}), never broadcast. The packet carries no playback cap: the
 *       system's lifetime is its own asset's, so an endless system attached this way never stops,
 *       and a caller with one keeps it world-positioned. Answers how many viewers received it, zero
 *       for an entity the tracker has not shown anyone yet (a fresh spawn), so a caller can fall
 *       back to a positional spawn at the entity's place.</li>
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
     * The full-arity world-positioned spawn: {@code yaw}/{@code pitch}/{@code roll} in radians,
     * a uniform {@code scale}, an optional {@code color} tint (null keeps the system's authored
     * colours) and the client-playback cap {@code maxDurationSeconds} ({@code <= 0} = uncapped),
     * broadcast to every player within {@link ParticleUtil#DEFAULT_PARTICLE_DISTANCE}. The one
     * engine overload that takes both the tint and the cap, so a tinted burst keeps the leak guard
     * the capped {@link #spawnAt(Store, String, Vector3d, float)} form gives an untinted one. No-op
     * ({@code false}) for a null asset id or on any error.
     */
    public static boolean spawnAt(@Nonnull Store<EntityStore> store, @Nullable String particleAsset,
                                  @Nonnull Vector3d position, float yaw, float pitch, float roll, float scale,
                                  @Nullable Color color, float maxDurationSeconds) {
        if (particleAsset == null) return false;
        try {
            List<Ref<EntityStore>> playerRefs = nearbyPlayers(store, position);
            ParticleUtil.spawnParticleEffect(particleAsset, position.x(), position.y(), position.z(),
                    yaw, pitch, roll, scale, color, null, playerRefs, store, maxDurationSeconds);
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
     */
    public static boolean spawnDirectional(@Nonnull Store<EntityStore> store,
                                           @Nullable String particleAsset,
                                           @Nonnull Vector3d position,
                                           @Nonnull Rotation3f rotation) {
        if (particleAsset == null) return false;
        try {
            List<Ref<EntityStore>> playerRefs = nearbyPlayers(store, position);
            ParticleUtil.spawnParticleEffect(particleAsset, position, rotation, playerRefs, store);
            return true;
        } catch (Throwable t) {
            fine("directional particle (" + particleAsset + ") failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * ONE entity-attached particle system, the leaf set of the protocol's own {@code ModelParticle}
     * in library types: which system, how big, an optional tint, which part of the entity it rides
     * ({@link EntityPart#Self} the whole entity, {@link EntityPart#Entity} its model, the two item
     * parts its held items) and optionally which named node of that model, a local offset and
     * rotation, whether it stays behind when the model moves, and whether it is cleared when the
     * entity is removed. Build one with {@link #of} and the {@code with*} copies.
     */
    public record AttachedParticle(@Nonnull String systemId, float scale, @Nullable Color color,
            @Nonnull EntityPart part, @Nullable String nodeName, @Nullable Vector3fc positionOffset,
            @Nullable Direction rotationOffset, boolean detachedFromModel, boolean clearParticlesOnRemove) {

        /** A default-tuned system riding the whole entity: scale 1, no tint, no offset, cleared with the entity. */
        @Nonnull
        public static AttachedParticle of(@Nonnull String systemId) {
            return new AttachedParticle(systemId, 1f, null, EntityPart.Self, null, null, null, false, true);
        }

        @Nonnull
        public AttachedParticle withScale(float scale) {
            return new AttachedParticle(systemId, scale, color, part, nodeName, positionOffset, rotationOffset,
                    detachedFromModel, clearParticlesOnRemove);
        }

        @Nonnull
        public AttachedParticle withColor(@Nullable Color color) {
            return new AttachedParticle(systemId, scale, color, part, nodeName, positionOffset, rotationOffset,
                    detachedFromModel, clearParticlesOnRemove);
        }

        /** Ride {@code part}, and when {@code nodeName} is given, that named node of the model. */
        @Nonnull
        public AttachedParticle at(@Nonnull EntityPart part, @Nullable String nodeName) {
            return new AttachedParticle(systemId, scale, color, part, nodeName, positionOffset, rotationOffset,
                    detachedFromModel, clearParticlesOnRemove);
        }

        @Nonnull
        public AttachedParticle withOffsets(@Nullable Vector3fc positionOffset, @Nullable Direction rotationOffset) {
            return new AttachedParticle(systemId, scale, color, part, nodeName, positionOffset, rotationOffset,
                    detachedFromModel, clearParticlesOnRemove);
        }

        /** The protocol leaf this record maps to, field for field. */
        @Nonnull
        public ModelParticle toProtocol() {
            return new ModelParticle(systemId, scale, color, part, nodeName, positionOffset, rotationOffset,
                    detachedFromModel, clearParticlesOnRemove);
        }
    }

    /**
     * Attach {@code particles} to {@code entity} for every player whose tracker currently shows it
     * ({@code SpawnModelParticles} on the entity's own {@code NetworkId}, delivered through
     * {@link EntityViewers#deliver}). Answers how many viewers received the packet: zero for a null
     * or invalid ref, an entity with no network id, an empty or null-only list, an entity the
     * tracker has not yet shown anyone (spawned this tick), one nobody is near, or any error. A
     * caller that must not lose the cue falls back on zero to a positional spawn at the entity's
     * place. No playback cap exists on this route; see the class javadoc.
     */
    public static int spawnOn(@Nonnull Store<EntityStore> store, @Nullable Ref<EntityStore> entity,
                              @Nullable List<AttachedParticle> particles) {
        if (entity == null || !entity.isValid() || particles == null || particles.isEmpty()) {
            return 0;
        }
        try {
            List<ModelParticle> protocol = new ArrayList<>(particles.size());
            for (AttachedParticle particle : particles) {
                if (particle != null && particle.systemId() != null && !particle.systemId().isBlank()) {
                    protocol.add(particle.toProtocol());
                }
            }
            if (protocol.isEmpty()) {
                return 0;
            }
            NetworkId networkId = store.getComponent(entity, NetworkId.getComponentType());
            if (networkId == null) {
                fine("attached particle skipped: the entity has no NetworkId");
                return 0;
            }
            return EntityViewers.deliver(store, entity,
                    new SpawnModelParticles(networkId.getId(), protocol.toArray(new ModelParticle[0])));
        } catch (Throwable t) {
            fine("attached particle failed: " + t.getMessage());
            return 0;
        }
    }

    /**
     * A particle tint from a {@code #rrggbb} hex (the hash optional, either case, surrounding
     * space ignored), or null for a null, blank or malformed value, so a caller passes an authored
     * colour leaf straight through and an unreadable one plays the system's own colours.
     */
    @Nullable
    public static Color tint(@Nullable String hex) {
        if (hex == null) {
            return null;
        }
        String digits = hex.trim();
        if (digits.startsWith("#")) {
            digits = digits.substring(1);
        }
        if (digits.length() != 6) {
            return null;
        }
        for (int i = 0; i < digits.length(); i++) {
            if (Character.digit(digits.charAt(i), 16) < 0) {
                return null;
            }
        }
        return new Color((byte) Integer.parseInt(digits.substring(0, 2), 16),
                (byte) Integer.parseInt(digits.substring(2, 4), 16),
                (byte) Integer.parseInt(digits.substring(4, 6), 16));
    }

    /** The players within {@link ParticleUtil#DEFAULT_PARTICLE_DISTANCE} of {@code position}. */
    @Nonnull
    private static List<Ref<EntityStore>> nearbyPlayers(@Nonnull Store<EntityStore> store, @Nonnull Vector3d position) {
        // A plain ArrayList rather than the engine's SpatialResource.getThreadLocalReferenceList():
        // that method's fastutil return type is binary-incompatible between the compile-time API and
        // the live runtime, and collect accepts the standard List interface.
        SpatialResource<Ref<EntityStore>, EntityStore> playerSpatial =
                store.getResource(EntityModule.get().getPlayerSpatialResourceType());
        List<Ref<EntityStore>> playerRefs = new ArrayList<>();
        playerSpatial.getSpatialStructure().collect(position, ParticleUtil.DEFAULT_PARTICLE_DISTANCE, playerRefs);
        return playerRefs;
    }

    private static void fine(@Nonnull String message) {
        try {
            CommonLog.LOGGER.atFine().log("[ziggfreed-common][particle] " + message);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM: the flogger LOGGER can throw; swallow it.
        }
    }
}
