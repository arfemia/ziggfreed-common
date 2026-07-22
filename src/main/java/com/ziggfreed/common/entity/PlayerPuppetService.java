package com.ziggfreed.common.entity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.protocol.PlayerSkin;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.cosmetics.CosmeticsModule;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.ActiveAnimationComponent;
import com.hypixel.hytale.server.core.modules.entity.component.EntityScaleComponent;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.ZiggfreedCommonPlugin;

/**
 * Spawns / despawns / animates a networked PUPPET entity cloning a live player's
 * {@link PlayerSkin}, plus the paired SCALE self-hide primitive - the two generic mechanisms a
 * "mount the player, hide their body, show a puppet performing the work instead" presentation
 * needs. Lifted config-free out of a consumer's in-game P0 spike harness (RPG Stations'
 * {@code puppetspike.PuppetSpikeService}, source-traced against the engine's own sanctioned
 * {@code /npc spawn --randommodel} exemplar), so any mod builds the SAME proven mechanism instead
 * of re-deriving it: {@code PlayerSkin} copy ctor -&gt; {@link PlayerSkinComponent} -&gt;
 * {@code CosmeticsModule.createModel} for the look, a bare {@link InventoryComponent.Hotbar} for
 * held-item mirroring (the generic "any entity carrying Hotbar + Visible gets networked
 * equipment" mechanism - {@code InventorySystems.SyncEquipmentSystem} has no player-type gate),
 * and an explicit {@link NetworkId} + a pre-seeded {@link ActiveAnimationComponent} for the
 * render-guaranteed animation surface (a viewer whose tracker marks the puppet newlyVisible on
 * ANY later tick auto-catches-up to the tracked clip via the engine's own
 * {@code ModelSystems.AnimationEntityTrackerUpdate}, independent of exactly when a direct
 * {@link #playAnimation} packet happens to land - the spike's own round-4 fix, source-confirmed
 * against {@code AnimationUtils.playAnimation}'s {@code PlayerUtil.forEachPlayerThatCanSeeEntity}
 * viewer filter).
 *
 * <p><b>Scope, deliberately narrow.</b> This class owns the SPAWN MECHANISM (skin clone, held-item
 * mirror, animation surface, placement, {@code NonSerialized}, despawn) and the SCALE hide/reveal
 * pair. It does NOT own: WHERE to place the puppet (offset/yaw resolution against a station's own
 * anchor concept is caller policy), WHICH look/hide route an author picked (a station's
 * {@code Puppet.Look}/{@code Puppet.Hide} knob resolution is caller policy - this class supplies
 * only the {@code PlayerClone} look and the {@code Scale} hide mechanism, the two routes the
 * puppet-presentation design's in-game spike actually crowned), or the swing-beat CADENCE (the
 * caller owns its own repeat scheduling, mirroring the station engine's own per-swing timer
 * convention - this class exposes the single-shot {@link #playAnimation} primitive underneath
 * it). A {@code ModelSwap}-route hide's revert already exists as {@link PlayerModelService#restore}
 * - this class does not duplicate it.
 *
 * <p><b>Threading / accessor shape.</b> Every spawn-side call takes a
 * {@link ComponentAccessor}{@code <EntityStore>} - the interface BOTH {@link Store} AND
 * {@link CommandBuffer} implement (the same seam {@code cast.HitContext} uses) - so a caller
 * inside an interaction-handler / tick processing lock (which must defer entity mutation to a
 * {@code CommandBuffer}) and a caller with a live {@code Store} (e.g. a command handler's
 * {@code World#execute} hop) both work through ONE method, with no accessor-specific overload
 * pair to keep in sync. {@link #despawn} needs the CONCRETE type (the interface's
 * {@code removeEntity} requires a {@code Holder} neither caller has at despawn time), so it is
 * overloaded on {@link Store}/{@link CommandBuffer} directly. WORLD-THREAD ONLY for every method;
 * every engine-touching call is try-guarded so a bad ref / missing component degrades to a no-op
 * (never {@code null}/{@code false}/no-op the pattern this whole package follows) and never
 * throws into the caller.
 */
public final class PlayerPuppetService {

    /** "Near-zero", not literally zero - avoids a stray divide-by-zero downstream. */
    private static final float DEFAULT_NEAR_ZERO_SCALE = 0.01f;

    private PlayerPuppetService() {
    }

    /** The near-zero scale {@link #hideByScale} defaults to when no explicit scale is given. */
    public static float nearZeroScale() {
        return DEFAULT_NEAR_ZERO_SCALE;
    }

    // ==================== spawn / despawn ====================

    /**
     * Spawns a puppet entity per {@code req}: clones {@code req.sourceRef()}'s live
     * {@link PlayerSkin} onto a new networked entity at {@code req.position()}/
     * {@code req.yawRadians()}, optionally mirroring held-item + pre-seeding an animation clip,
     * marked {@code NonSerialized} (never survives a restart - a crash loses the puppet, the same
     * session-scoped lifecycle every transient display/anchor primitive in this ecosystem
     * accepts). Never throws; returns {@code null} on any failure (a source ref with no
     * {@link PlayerSkinComponent}, or an {@code addEntity} rejection) - the caller treats a null
     * return as "no puppet this time", never a hard error.
     */
    @Nullable
    public static Ref<EntityStore> spawn(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull PuppetSpawnRequest req) {
        try {
            Ref<EntityStore> sourceRef = req.sourceRef();
            PlayerSkinComponent sourceSkin = accessor.getComponent(sourceRef, PlayerSkinComponent.getComponentType());
            if (sourceSkin == null) {
                fine("spawn skipped: source has no PlayerSkinComponent");
                return null;
            }

            Rotation3f rotation = new Rotation3f(0f, req.yawRadians(), 0f);
            Holder<EntityStore> holder = EntityStore.REGISTRY.newHolder();
            holder.addComponent(TransformComponent.getComponentType(), new TransformComponent(req.position(), rotation));
            holder.ensureComponent(UUIDComponent.getComponentType());
            holder.addComponent(NetworkId.getComponentType(), new NetworkId(accessor.getExternalData().takeNextNetworkId()));
            holder.ensureComponent(EntityTrackerSystems.Visible.getComponentType());

            PlayerSkin puppetSkin = new PlayerSkin(sourceSkin.getPlayerSkin());
            holder.addComponent(PlayerSkinComponent.getComponentType(), new PlayerSkinComponent(puppetSkin));
            Model model = CosmeticsModule.get().createModel(puppetSkin);
            if (model != null) {
                holder.addComponent(ModelComponent.getComponentType(), new ModelComponent(model));
            }

            String heldItemId = resolveHeldItemId(accessor, sourceRef, req);
            if (heldItemId != null) {
                SimpleItemContainer container = new SimpleItemContainer((short) 1);
                container.setItemStackForSlot((short) 0, new ItemStack(heldItemId, 1));
                holder.addComponent(InventoryComponent.Hotbar.getComponentType(),
                        new InventoryComponent.Hotbar(container, (byte) 0));
            }

            if (req.initialAnimationSlot() != null && req.initialClipId() != null) {
                ActiveAnimationComponent activeAnim = new ActiveAnimationComponent();
                activeAnim.setPlayingAnimation(req.initialAnimationSlot(), req.initialClipId());
                holder.addComponent(ActiveAnimationComponent.getComponentType(), activeAnim);
            }

            holder.ensureComponent(EntityStore.REGISTRY.getNonSerializedComponentType());

            Ref<EntityStore> puppetRef = accessor.addEntity(holder, AddReason.SPAWN);
            if (puppetRef == null) {
                warn("spawn: addEntity returned null ref", null);
            }
            return puppetRef;
        } catch (Throwable t) {
            warn("spawn failed: " + t.getMessage(), t);
            return null;
        }
    }

    @Nullable
    private static String resolveHeldItemId(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> sourceRef, @Nonnull PuppetSpawnRequest req) {
        if (req.heldItemIdOverride() != null) {
            return req.heldItemIdOverride().isBlank() ? null : req.heldItemIdOverride();
        }
        if (!req.mirrorHeldItem()) {
            return null;
        }
        try {
            ItemStack held = InventoryComponent.getItemInHand(accessor, sourceRef);
            return held != null ? held.getItemId() : null;
        } catch (Throwable t) {
            fine("held-item mirror read failed: " + t.getMessage());
            return null;
        }
    }

    /** Despawns {@code puppetRef} via a live {@link Store}. No-op (never throws) when already gone. */
    public static void despawn(@Nullable Ref<EntityStore> puppetRef, @Nonnull Store<EntityStore> store) {
        if (puppetRef == null || !puppetRef.isValid()) {
            return;
        }
        try {
            store.removeEntity(puppetRef, RemoveReason.REMOVE);
        } catch (Throwable t) {
            warn("despawn failed: " + t.getMessage(), t);
        }
    }

    /**
     * Despawns {@code puppetRef} via a {@link CommandBuffer} (the tick-safe route for a caller
     * inside an interaction-handler / tick processing lock, where a direct
     * {@code store.removeEntity} throws {@code IllegalStateException("Store is currently
     * processing!")}). No-op (never throws) when already gone or {@code commandBuffer} is null.
     */
    public static void despawn(@Nullable Ref<EntityStore> puppetRef, @Nullable CommandBuffer<EntityStore> commandBuffer) {
        if (puppetRef == null || !puppetRef.isValid() || commandBuffer == null) {
            return;
        }
        try {
            commandBuffer.removeEntity(puppetRef, RemoveReason.REMOVE);
        } catch (Throwable t) {
            warn("despawn failed: " + t.getMessage(), t);
        }
    }

    // ==================== animation ====================

    /**
     * Fires (or re-fires) a single animation packet on {@code puppetRef} - the caller owns the
     * repeat CADENCE (a beat loop, mirroring a station engine's own per-swing timer convention);
     * this is the single-shot primitive underneath it. Combined with
     * {@link PuppetSpawnRequest.Builder#initialAnimation}'s holder pre-seed, a viewer sees the
     * clip whether they were already tracking the puppet at spawn time or became visible later.
     * No-op (never throws) on an invalid ref or any engine failure.
     */
    public static void playAnimation(@Nonnull ComponentAccessor<EntityStore> accessor, @Nullable Ref<EntityStore> puppetRef,
            @Nonnull AnimationSlot slot, @Nullable String itemAnimationsId, @Nonnull String clipId, boolean sendToSelf) {
        if (puppetRef == null || !puppetRef.isValid()) {
            return;
        }
        try {
            AnimationUtils.playAnimation(puppetRef, slot, itemAnimationsId, clipId, sendToSelf, accessor);
        } catch (Throwable t) {
            fine("playAnimation failed: " + t.getMessage());
        }
    }

    // ==================== scale self-hide ====================

    /**
     * Hides {@code ref} by scaling it to {@link #nearZeroScale()} (the in-game-crowned
     * {@code Hide.Route: "Scale"} mechanism - confirmed to fully hide the entity's own rendered
     * body in both first- and third-person, including the held item, unlike a
     * {@code ModelComponent} swap). Returns the PRIOR {@link EntityScaleComponent} scale to pass
     * back into {@link #revealByScale} - {@code null} means no such component existed before
     * (revert should REMOVE the component, not set it to {@code 1.0}). Best-effort: a failure
     * (should not happen for a live ref) also returns {@code null}, which degrades to the same
     * harmless "nothing to restore, just remove" revert path since a failed apply left no
     * lingering scale component to begin with.
     */
    @Nullable
    public static Float hideByScale(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref) {
        return hideByScale(accessor, ref, DEFAULT_NEAR_ZERO_SCALE);
    }

    /** {@link #hideByScale(ComponentAccessor, Ref)} with an explicit near-zero scale. */
    @Nullable
    public static Float hideByScale(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref,
            float nearZeroScale) {
        try {
            EntityScaleComponent existing = accessor.getComponent(ref, EntityScaleComponent.getComponentType());
            Float prior = existing != null ? existing.getScale() : null;
            accessor.putComponent(ref, EntityScaleComponent.getComponentType(), new EntityScaleComponent(nearZeroScale));
            return prior;
        } catch (Throwable t) {
            fine("hideByScale failed: " + t.getMessage());
            return null;
        }
    }

    /**
     * Reverts {@link #hideByScale}: restores {@code priorScale} if one was captured, else REMOVES
     * the {@link EntityScaleComponent} entirely (matching the entity's true pre-hide baseline, not
     * a hardcoded {@code 1.0}). No-op (never throws) on an invalid ref or any engine failure.
     *
     * <p>For the {@code ModelSwap} hide route's revert, use the existing
     * {@link PlayerModelService#restore} instead - this class does not duplicate it.
     */
    public static void revealByScale(@Nonnull ComponentAccessor<EntityStore> accessor, @Nullable Ref<EntityStore> ref,
            @Nullable Float priorScale) {
        if (ref == null || !ref.isValid()) {
            return;
        }
        try {
            if (priorScale != null) {
                accessor.putComponent(ref, EntityScaleComponent.getComponentType(), new EntityScaleComponent(priorScale));
            } else {
                accessor.tryRemoveComponent(ref, EntityScaleComponent.getComponentType());
            }
        } catch (Throwable t) {
            warn("revealByScale failed: " + t.getMessage(), t);
        }
    }

    // ==================== pure cores (unit-tested without a live server) ====================

    /**
     * Pure: an anchor position shifted by an XYZ offset. Kept a primitive-typed return (not
     * {@link Vector3d}) so it stays unit-testable without a running Hytale server, mirroring the
     * discipline every other spatial helper in this ecosystem's station work already established
     * (e.g. a station's own block-top-anchor + {@code Custody.Display.Offset} resolver). A caller
     * builds the actual {@link Vector3d} for {@link PuppetSpawnRequest.Builder#position} from
     * this.
     */
    @Nonnull
    public static double[] offsetPosition(double anchorX, double anchorY, double anchorZ,
            double offsetX, double offsetY, double offsetZ) {
        return new double[] {anchorX + offsetX, anchorY + offsetY, anchorZ + offsetZ};
    }

    /** Pure: degrees to radians, for a puppet's authored world-space {@code Yaw}. */
    public static float yawRadiansFromDegrees(double degrees) {
        return (float) Math.toRadians(degrees);
    }

    // ==================== spawn request ====================

    /**
     * Immutable per-spawn request. Built once per puppet spawn call; mirrors the
     * {@code cast.HitResolver.HitRequest} builder shape this codebase already uses for a
     * similarly multi-knob engine operation.
     */
    public static final class PuppetSpawnRequest {
        private final Ref<EntityStore> sourceRef;
        private final Vector3d position;
        private final float yawRadians;
        private final boolean mirrorHeldItem;
        @Nullable
        private final String heldItemIdOverride;
        @Nullable
        private final AnimationSlot initialAnimationSlot;
        @Nullable
        private final String initialClipId;

        private PuppetSpawnRequest(@Nonnull Builder b) {
            this.sourceRef = b.sourceRef;
            this.position = b.position;
            this.yawRadians = b.yawRadians;
            this.mirrorHeldItem = b.mirrorHeldItem;
            this.heldItemIdOverride = b.heldItemIdOverride;
            this.initialAnimationSlot = b.initialAnimationSlot;
            this.initialClipId = b.initialClipId;
        }

        /** The player whose live {@link PlayerSkin} (and, optionally, held item) the puppet clones. */
        @Nonnull
        public Ref<EntityStore> sourceRef() {
            return sourceRef;
        }

        /** The puppet's spawn position. */
        @Nonnull
        public Vector3d position() {
            return position;
        }

        /** The puppet's facing yaw in radians (world-space). */
        public float yawRadians() {
            return yawRadians;
        }

        /** Whether to mirror the source's currently-held item onto the puppet's Hotbar. */
        public boolean mirrorHeldItem() {
            return mirrorHeldItem;
        }

        /** A forced held-item id overriding {@link #mirrorHeldItem()}, or {@code null}. */
        @Nullable
        public String heldItemIdOverride() {
            return heldItemIdOverride;
        }

        /** The slot to pre-seed {@link ActiveAnimationComponent} on, or {@code null} for none. */
        @Nullable
        public AnimationSlot initialAnimationSlot() {
            return initialAnimationSlot;
        }

        /** The clip id to pre-seed on {@link #initialAnimationSlot()}, or {@code null} for none. */
        @Nullable
        public String initialClipId() {
            return initialClipId;
        }

        @Nonnull
        public static Builder builder() {
            return new Builder();
        }

        /** Fluent builder for a {@link PuppetSpawnRequest}. */
        public static final class Builder {
            private Ref<EntityStore> sourceRef;
            private Vector3d position;
            private float yawRadians;
            private boolean mirrorHeldItem;
            @Nullable
            private String heldItemIdOverride;
            @Nullable
            private AnimationSlot initialAnimationSlot;
            @Nullable
            private String initialClipId;

            /** The player whose live skin the puppet clones. Required. */
            @Nonnull
            public Builder sourceRef(@Nonnull Ref<EntityStore> sourceRef) {
                this.sourceRef = sourceRef;
                return this;
            }

            /** The puppet's spawn position. Required. */
            @Nonnull
            public Builder position(@Nonnull Vector3d position) {
                this.position = position;
                return this;
            }

            /** The puppet's facing yaw in radians (world-space); default {@code 0}. */
            @Nonnull
            public Builder yawRadians(float yawRadians) {
                this.yawRadians = yawRadians;
                return this;
            }

            /**
             * Mirror the source's CURRENTLY-held item onto the puppet's own bare Hotbar (slot 0).
             * Mutually exclusive with {@link #heldItemIdOverride} (last set wins). Default
             * {@code false} (empty-handed).
             */
            @Nonnull
            public Builder mirrorHeldItem(boolean mirror) {
                this.mirrorHeldItem = mirror;
                if (mirror) {
                    this.heldItemIdOverride = null;
                }
                return this;
            }

            /**
             * Force a specific item id onto the puppet's Hotbar regardless of what the source
             * holds (a fixed-prop look). Mutually exclusive with {@link #mirrorHeldItem} (last
             * set wins); {@code null}/blank leaves the puppet empty-handed.
             */
            @Nonnull
            public Builder heldItemIdOverride(@Nullable String itemId) {
                this.heldItemIdOverride = itemId;
                this.mirrorHeldItem = false;
                return this;
            }

            /**
             * Pre-seeds {@link ActiveAnimationComponent} on the spawn holder with {@code clipId}
             * on {@code slot} - the render-guaranteed route (see this outer class's header
             * javadoc). Pass both {@code null} to skip the pre-seed (the default); a caller that
             * sets one but not the other is treated as "no pre-seed" ({@link #spawn} checks both
             * non-null).
             */
            @Nonnull
            public Builder initialAnimation(@Nullable AnimationSlot slot, @Nullable String clipId) {
                this.initialAnimationSlot = slot;
                this.initialClipId = clipId;
                return this;
            }

            @Nonnull
            public PuppetSpawnRequest build() {
                return new PuppetSpawnRequest(this);
            }
        }
    }

    // ==================== logging ====================

    private static void warn(@Nonnull String message, @Nullable Throwable cause) {
        try {
            if (cause != null) {
                ZiggfreedCommonPlugin.LOGGER.atWarning().withCause(cause)
                        .log("[ziggfreed-common][puppet] " + message);
            } else {
                ZiggfreedCommonPlugin.LOGGER.atWarning().log("[ziggfreed-common][puppet] " + message);
            }
        } catch (Throwable ignored) {
            // log-manager-less unit JVM: the flogger LOGGER can throw; swallow it.
        }
    }

    private static void fine(@Nonnull String message) {
        try {
            ZiggfreedCommonPlugin.LOGGER.atFine().log("[ziggfreed-common][puppet] " + message);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM: the flogger LOGGER can throw; swallow it.
        }
    }
}
