package com.ziggfreed.common.entity.performer;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The one-shot descriptor a caller hands {@link StationPerformer#spawn} - the live engine handles
 * (world + mutation accessor + optional live store) the performer uses to create the double PLUS the
 * immutable config for the spawn (owner, station binding, placement, {@link PerformerLook}, optional
 * initial prop/clip). All engine reads are WORLD-THREAD, re-validated + try-guarded per call.
 *
 * <p><b>The spawn accessor seam (accessor + store).</b> Spawning a double must work from inside a
 * caller's write-processing lock (an interaction handler / the heartbeat frame drain), where a
 * direct {@code store.addEntity} throws {@code IllegalStateException("Store is currently
 * processing!")}. So the ONE required handle is {@link #accessor()}, a
 * {@link ComponentAccessor}{@code <EntityStore>} - the interface BOTH {@link Store} AND
 * {@link CommandBuffer} implement, exactly the seam {@code PlayerPuppetService.spawn} accepts. A
 * lock-held caller passes its {@link CommandBuffer} (the {@link HolderPerformer} then spawns the
 * puppet tick-safely, byte-parity with the shipped {@code StationPuppetController}); an unlocked
 * caller passes its live {@link Store} (a {@code Store} IS a {@code ComponentAccessor}).
 *
 * <p>{@link #store()} is a SEPARATE, {@code @Nullable} live {@code Store} an unlocked caller MAY
 * also provide: the {@link NpcRolePerformer} backend needs a concrete {@code Store} for
 * {@code NPCPlugin.spawnEntity} (which does not accept a {@code CommandBuffer}), so when a caller
 * has an unlocked live store it hands it here for a synchronous NPC spawn; when only an accessor is
 * present (a lock-held {@code CommandBuffer}) the NPC backend DEFERS its spawn to the next safe
 * world-thread moment. Use {@link Builder#liveStore(Store)} to set both from one unlocked store.
 *
 * <p>{@link #world()} is only needed by the bare-{@code Holder} backend's walk (path solve over
 * {@code CollisionModule}) and by the NPC backend's deferred-spawn hop ({@code world.execute}); it
 * may be {@code null} for a stationary Holder that never walks and is spawned synchronously.
 */
public final class PerformerSpawnCtx {

    @Nullable
    private final World world;
    @Nonnull
    private final ComponentAccessor<EntityStore> accessor;
    @Nullable
    private final Store<EntityStore> store;
    @Nonnull
    private final Ref<EntityStore> ownerRef;
    @Nonnull
    private final UUID ownerUuid;
    @Nonnull
    private final String stationKey;
    @Nonnull
    private final Vector3d position;
    private final float yawRadians;
    @Nonnull
    private final PerformerLook look;
    @Nullable
    private final PropSpec initialProp;
    @Nullable
    private final ClipSpec initialClip;

    private PerformerSpawnCtx(@Nonnull Builder b) {
        this.world = b.world;
        this.accessor = b.accessor;
        this.store = b.store;
        this.ownerRef = b.ownerRef;
        this.ownerUuid = b.ownerUuid;
        this.stationKey = b.stationKey;
        this.position = b.position;
        this.yawRadians = b.yawRadians;
        this.look = b.look;
        this.initialProp = b.initialProp;
        this.initialClip = b.initialClip;
    }

    /** The world the performer lives in (null for a stationary performer that never walks). */
    @Nullable
    public World world() {
        return world;
    }

    /**
     * The REQUIRED spawn/mutation accessor - a {@link Store} (unlocked caller) or a
     * {@link CommandBuffer} (lock-held caller). The bare-{@code Holder} backend threads it straight
     * into {@code PlayerPuppetService.spawn} so the puppet spawns tick-safely from inside a
     * processing lock.
     */
    @Nonnull
    public ComponentAccessor<EntityStore> accessor() {
        return accessor;
    }

    /**
     * An optional live entity {@link Store} an UNLOCKED caller may also provide - the concrete store
     * the {@link NpcRolePerformer} backend needs for {@code NPCPlugin.spawnEntity}. {@code null} when
     * only {@link #accessor()} is present (a lock-held {@code CommandBuffer}), in which case the NPC
     * backend defers its spawn to the next safe world-thread moment.
     */
    @Nullable
    public Store<EntityStore> store() {
        return store;
    }

    /** The owning player (source of the cloned skin / mirrored held item). */
    @Nonnull
    public Ref<EntityStore> ownerRef() {
        return ownerRef;
    }

    /** The owning session's UUID (stamped onto {@link PerformerIdentityComponent}). */
    @Nonnull
    public UUID ownerUuid() {
        return ownerUuid;
    }

    /** The station block key the performer belongs to (stamped onto the identity component). */
    @Nonnull
    public String stationKey() {
        return stationKey;
    }

    /** The spawn/anchor position. */
    @Nonnull
    public Vector3d position() {
        return position;
    }

    /** The spawn facing yaw, radians (world-space). */
    public float yawRadians() {
        return yawRadians;
    }

    /** The resolved appearance/behaviour config. */
    @Nonnull
    public PerformerLook look() {
        return look;
    }

    /** An initial held prop, or {@code null} for empty hands at spawn. */
    @Nullable
    public PropSpec initialProp() {
        return initialProp;
    }

    /** An initial pre-seeded work clip (the render-guaranteed spawn animation), or {@code null}. */
    @Nullable
    public ClipSpec initialClip() {
        return initialClip;
    }

    @Nonnull
    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder for a {@link PerformerSpawnCtx}. */
    public static final class Builder {
        @Nullable
        private World world;
        private ComponentAccessor<EntityStore> accessor;
        @Nullable
        private Store<EntityStore> store;
        private Ref<EntityStore> ownerRef;
        private UUID ownerUuid;
        private String stationKey;
        private Vector3d position;
        private float yawRadians;
        @Nonnull
        private PerformerLook look = PerformerLook.playerClone();
        @Nullable
        private PropSpec initialProp;
        @Nullable
        private ClipSpec initialClip;

        @Nonnull
        public Builder world(@Nullable World world) {
            this.world = world;
            return this;
        }

        /**
         * The REQUIRED spawn accessor - a live {@link Store} (unlocked caller) or a
         * {@link CommandBuffer} (lock-held caller). A {@code Store} caller that also wants the NPC
         * backend's synchronous concrete-store spawn should use {@link #liveStore(Store)} instead
         * (it sets both this accessor and {@link #store(Store)} from the one store).
         */
        @Nonnull
        public Builder accessor(@Nonnull ComponentAccessor<EntityStore> accessor) {
            this.accessor = accessor;
            return this;
        }

        /**
         * An optional live {@link Store} an UNLOCKED caller may provide for the NPC backend's
         * synchronous {@code NPCPlugin.spawnEntity}. Leave unset (the default {@code null}) for a
         * lock-held caller that only has a {@link CommandBuffer} - the NPC backend then defers its
         * spawn to the next safe world-thread moment.
         */
        @Nonnull
        public Builder store(@Nullable Store<EntityStore> store) {
            this.store = store;
            return this;
        }

        /**
         * Convenience for an UNLOCKED caller holding one live {@link Store}: sets it as BOTH the
         * spawn {@link #accessor(ComponentAccessor) accessor} (a {@code Store} is a
         * {@code ComponentAccessor}) AND the concrete {@link #store(Store) store}, so both backends
         * spawn synchronously (the Holder via the store-as-accessor, the NPC via the concrete
         * store). Do NOT use from a lock-held caller - pass {@link #accessor(ComponentAccessor)}
         * with a {@code CommandBuffer} and leave the store unset there.
         */
        @Nonnull
        public Builder liveStore(@Nonnull Store<EntityStore> store) {
            this.accessor = store;
            this.store = store;
            return this;
        }

        @Nonnull
        public Builder ownerRef(@Nonnull Ref<EntityStore> ownerRef) {
            this.ownerRef = ownerRef;
            return this;
        }

        @Nonnull
        public Builder ownerUuid(@Nonnull UUID ownerUuid) {
            this.ownerUuid = ownerUuid;
            return this;
        }

        @Nonnull
        public Builder stationKey(@Nonnull String stationKey) {
            this.stationKey = stationKey;
            return this;
        }

        @Nonnull
        public Builder position(@Nonnull Vector3d position) {
            this.position = position;
            return this;
        }

        @Nonnull
        public Builder yawRadians(float yawRadians) {
            this.yawRadians = yawRadians;
            return this;
        }

        @Nonnull
        public Builder look(@Nonnull PerformerLook look) {
            this.look = look;
            return this;
        }

        @Nonnull
        public Builder initialProp(@Nullable PropSpec initialProp) {
            this.initialProp = initialProp;
            return this;
        }

        @Nonnull
        public Builder initialClip(@Nullable ClipSpec initialClip) {
            this.initialClip = initialClip;
            return this;
        }

        @Nonnull
        public PerformerSpawnCtx build() {
            return new PerformerSpawnCtx(this);
        }
    }
}
