package com.ziggfreed.common.entity.performer;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.ZiggfreedCommonPlugin;
import com.ziggfreed.common.entity.PlayerModelService;
import com.ziggfreed.common.entity.PlayerPuppetService;
import com.ziggfreed.common.entity.PlayerPuppetService.PuppetSpawnRequest;
import com.ziggfreed.common.entity.PuppetNav;
import com.ziggfreed.common.entity.performer.PerformerLook.LookSource;

/**
 * The bare-{@code Holder} performer backend - the shipped, in-game-crowned puppet route re-expressed
 * behind {@link StationPerformer}, byte-parity with the existing controller for every existing
 * station. It owns no new mechanism: it drives the lifted {@link PlayerPuppetService} (skin clone,
 * held-item mirror, render-guaranteed animation, {@code Scale}-free spawn/despawn) + {@link PuppetNav}
 * (bounded A* walk over {@code CollisionModule}); this class supplies only the STATE (the spawned ref,
 * the last-mirrored prop for the dirty-gate) and the {@link PerformerIdentityComponent} attach.
 *
 * <p>Look sources: {@code PlayerClone} clones the live player skin (the proven default);
 * {@code Model} overlays a fixed authored model over the clone via {@link PlayerModelService#apply}
 * (falling to {@code fallbackModelId}, else leaving the clone - never a red-X). {@code NpcRole} does
 * not belong here (use {@link NpcRolePerformer}); a mis-routed {@code NpcRole} look degrades to a
 * player-clone puppet.
 *
 * <p><b>Spawn accessor (tick-safety):</b> {@link #spawn} threads {@link PerformerSpawnCtx#accessor()}
 * (a {@code Store} or a {@code CommandBuffer}) into {@code PlayerPuppetService.spawn}, so a lock-held
 * caller (inside {@code toggle()}/the heartbeat frame drain) spawns the puppet tick-safely, exactly
 * as the shipped {@code StationPuppetController} does.
 *
 * <p><b>Per-call mutation accessor (decision 55):</b> every later MUTATING method
 * ({@code despawn}/{@code presentAt}/{@code walkTo}/{@code setProp}/{@code playClip} + the returned
 * {@code WalkHandle.poll}) takes a FRESH per-call {@link ComponentAccessor} the wiring caller
 * threads from its own current frame - the crowned {@code StationPuppetController} pattern, since a
 * {@code CommandBuffer} is valid only for its own processing pass and cannot be captured across
 * frames. This class therefore captures NO store for its mutations. The captured {@code world}
 * (spawn-time) backs {@code walkTo}'s path solve; the captured {@code store}
 * ({@link PerformerSpawnCtx#store()}, {@code @Nullable}) backs ONLY the spawn-time fixed-{@code Model}
 * overlay (an unproven, unshipped look path), no-oping when a lock-held caller left it unset.
 *
 * <p>WORLD-THREAD ONLY; every engine call is try-guarded to a no-op, never a throw.
 */
public final class HolderPerformer implements StationPerformer {

    /** Arrival epsilon (blocks) for the bare-Holder arc-length walk. */
    private static final double ARRIVE_EPS = 0.05;

    @Nullable
    private World world;
    @Nullable
    private Store<EntityStore> store;
    @Nullable
    private Ref<EntityStore> puppetRef;
    @Nullable
    private String lastMirroredItemId;

    @Override
    public void spawn(@Nonnull PerformerSpawnCtx ctx) {
        this.world = ctx.world();
        this.store = ctx.store();
        PerformerLook look = ctx.look();
        try {
            PropSpec prop = ctx.initialProp();
            String heldOverride = prop != null && !prop.isEmpty() ? prop.itemId() : null;

            PuppetSpawnRequest.Builder b = PuppetSpawnRequest.builder()
                    .sourceRef(ctx.ownerRef())
                    .position(ctx.position())
                    .yawRadians(ctx.yawRadians())
                    .persist(look.persist());
            if (heldOverride != null) {
                b.heldItemIdOverride(heldOverride);
            }
            ClipSpec clip = ctx.initialClip();
            if (clip != null) {
                b.initialAnimation(clip.slot(), clip.clipId());
            }
            PuppetSpawnRequest req = b.build();

            PerformerIdentity identity = new PerformerIdentity(ctx.ownerUuid(), ctx.stationKey(),
                    PerformerKind.HOLDER, System.currentTimeMillis());

            // Spawn through ctx.accessor() (a Store OR a CommandBuffer), byte-parity with the
            // shipped StationPuppetController: a lock-held caller passes its CommandBuffer so the
            // puppet spawns tick-safely from inside the toggle()/heartbeat processing lock, where a
            // direct live-Store addEntity would throw IllegalStateException("Store is currently
            // processing!"). PlayerPuppetService.spawn takes the ComponentAccessor seam both types
            // implement.
            this.puppetRef = PlayerPuppetService.spawn(ctx.accessor(), req,
                    holder -> attachIdentity(holder, identity));
            this.lastMirroredItemId = heldOverride;

            // Model look: overlay a fixed authored model over the cloned puppet (resolution ladder:
            // modelId -> fallbackModelId -> leave the player-clone, never a red-X).
            if (puppetRef != null && look.source() == LookSource.MODEL) {
                applyFixedModel(look);
            }
        } catch (Throwable t) {
            warn("spawn failed: " + t.getMessage(), t);
        }
    }

    private void applyFixedModel(@Nonnull PerformerLook look) {
        Store<EntityStore> s = store;
        Ref<EntityStore> ref = puppetRef;
        if (s == null || ref == null) {
            return;
        }
        String modelId = look.modelId();
        if (modelId != null && PlayerModelService.apply(ref, s, modelId, 1.0f)) {
            return;
        }
        String fallback = look.fallbackModelId();
        if (fallback != null) {
            PlayerModelService.apply(ref, s, fallback, 1.0f);
        }
    }

    private static void attachIdentity(@Nonnull Holder<EntityStore> holder,
            @Nonnull PerformerIdentity identity) {
        var type = PerformerIdentityComponent.getComponentType();
        if (type == null) {
            return;
        }
        try {
            holder.addComponent(type, PerformerIdentityComponent.of(identity));
        } catch (Throwable t) {
            fine("attachIdentity failed: " + t.getMessage());
        }
    }

    @Override
    public void despawn(@Nonnull ComponentAccessor<EntityStore> accessor) {
        // PlayerPuppetService.despawn needs the CONCRETE type (ComponentAccessor.removeEntity wants
        // a Holder neither caller has), so dispatch on which accessor this frame handed us - the
        // CommandBuffer route is byte-parity with the shipped StationPuppetController.
        if (accessor instanceof CommandBuffer<EntityStore> cb) {
            PlayerPuppetService.despawn(puppetRef, cb);
        } else if (accessor instanceof Store<EntityStore> st) {
            PlayerPuppetService.despawn(puppetRef, st);
        }
        puppetRef = null;
    }

    @Override
    public void presentAt(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Vector3d pos, float yaw) {
        Ref<EntityStore> ref = puppetRef;
        if (ref == null || !ref.isValid()) {
            return;
        }
        try {
            TransformComponent tc = accessor.getComponent(ref, TransformComponent.getComponentType());
            if (tc != null) {
                tc.getPosition().set(pos.x, pos.y, pos.z);
                tc.getRotation().setYaw(yaw);
                // Mirror onto HeadRotation too - see PlayerPuppetService's spawn/walkTick pairing;
                // without this a re-presented (moved/re-faced) puppet's head stays at its spawn-time
                // yaw while the body snaps to the new facing.
                HeadRotation headRotation = accessor.getComponent(ref, HeadRotation.getComponentType());
                if (headRotation != null) {
                    headRotation.getRotation().setYaw(yaw);
                }
            }
        } catch (Throwable t) {
            fine("presentAt failed: " + t.getMessage());
        }
    }

    @Override
    @Nonnull
    public WalkHandle walkTo(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Vector3d target,
            double speedMps) {
        World w = world;
        Ref<EntityStore> ref = puppetRef;
        if (w == null || ref == null || !ref.isValid()) {
            return FailedWalkHandle.INSTANCE;
        }
        try {
            TransformComponent tc = accessor.getComponent(ref, TransformComponent.getComponentType());
            if (tc == null) {
                return FailedWalkHandle.INSTANCE;
            }
            Vector3d from = new Vector3d(tc.getPosition());
            List<Vector3d> waypoints = PuppetNav.solve(w, accessor, from, target, PuppetNav.DEFAULT_MAX_RADIUS);
            if (waypoints == null || waypoints.isEmpty()) {
                return FailedWalkHandle.INSTANCE;
            }
            PlayerPuppetService.setWalking(accessor, ref, true);
            return new HolderWalkHandle(waypoints, speedMps);
        } catch (Throwable t) {
            fine("walkTo failed: " + t.getMessage());
            return FailedWalkHandle.INSTANCE;
        }
    }

    @Override
    public void setProp(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull PropSpec prop) {
        Ref<EntityStore> ref = puppetRef;
        if (ref == null) {
            return;
        }
        String resolved = prop.isEmpty() ? null : prop.itemId();
        this.lastMirroredItemId = PlayerPuppetService.updateHeldItem(accessor, ref, lastMirroredItemId, resolved);
    }

    @Override
    public void playClip(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull ClipSpec clip) {
        Ref<EntityStore> ref = puppetRef;
        if (ref == null) {
            return;
        }
        PlayerPuppetService.playAnimation(accessor, ref, clip.slot(), clip.itemAnimationsId(),
                clip.clipId(), clip.sendToSelf());
    }

    @Override
    public boolean isAlive() {
        return puppetRef != null && puppetRef.isValid();
    }

    @Override
    @Nullable
    public Ref<EntityStore> ref() {
        return puppetRef;
    }

    /** Advances the puppet along a solved polyline via {@link PlayerPuppetService#walkTick}. */
    private final class HolderWalkHandle implements WalkHandle {
        @Nonnull
        private final List<Vector3d> waypoints;
        private final double speedMps;
        private final double total;
        private double progress;
        @Nonnull
        private State state = State.WALKING;

        HolderWalkHandle(@Nonnull List<Vector3d> waypoints, double speedMps) {
            this.waypoints = waypoints;
            this.speedMps = speedMps;
            this.total = PlayerPuppetService.pathLength(waypoints);
        }

        @Override
        @Nonnull
        public State poll(@Nonnull ComponentAccessor<EntityStore> accessor, double dtMs) {
            if (state != State.WALKING) {
                return state;
            }
            Ref<EntityStore> ref = puppetRef;
            if (ref == null || !ref.isValid()) {
                state = State.FAILED;
                return state;
            }
            progress = PlayerPuppetService.walkTick(accessor, ref, waypoints, progress, speedMps, dtMs);
            if (PlayerPuppetService.isArrived(progress, total, ARRIVE_EPS)) {
                PlayerPuppetService.setWalking(accessor, ref, false);
                state = State.ARRIVED;
            }
            return state;
        }

        @Override
        @Nonnull
        public State state() {
            return state;
        }

        @Override
        public void cancel() {
            // cancel() carries no accessor (decision 55 gives one only to poll), so it just latches
            // the terminal state; the walk's own MovementStates "walking" flag naturally settles when
            // no further walkTick advances it (the next poll returns FAILED without moving). No engine
            // mutation here - a stale captured CommandBuffer would be invalid, and a fresh accessor is
            // not available at cancel time.
            if (state == State.WALKING) {
                state = State.FAILED;
            }
        }
    }

    // ==================== logging ====================

    private static void warn(@Nonnull String message, @Nullable Throwable cause) {
        try {
            if (cause != null) {
                ZiggfreedCommonPlugin.LOGGER.atWarning().withCause(cause)
                        .log("[ziggfreed-common][performer] " + message);
            } else {
                ZiggfreedCommonPlugin.LOGGER.atWarning().log("[ziggfreed-common][performer] " + message);
            }
        } catch (Throwable ignored) {
            // log-manager-less unit JVM.
        }
    }

    private static void fine(@Nonnull String message) {
        try {
            ZiggfreedCommonPlugin.LOGGER.atFine().log("[ziggfreed-common][performer] " + message);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM.
        }
    }
}
