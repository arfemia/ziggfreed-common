package com.ziggfreed.common.entity.performer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.function.consumer.TriConsumer;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.PlayerSkin;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.cosmetics.CosmeticsModule;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.ActiveAnimationComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.util.InventoryHelper;
import com.ziggfreed.common.ZiggfreedCommonPlugin;
import com.ziggfreed.common.cast.WorldEvictors;
import com.ziggfreed.common.entity.performer.PerformerLook.SkinSource;

/**
 * The Role-driven {@code NPCEntity} performer backend - the spike-proven mechanism promoted to a
 * real {@link StationPerformer}. It buys native {@code MotionControllerWalk} gait / step-up + the
 * engine's own A* pathing, at the cost of NPC ceremony the role asset suppresses (no nameplate, no
 * F-prompt, {@code Invulnerable}). Grounded on the merged {@code NpcPerformerSpike} recipes:
 *
 * <ul>
 *   <li><b>spawn</b> - {@code NPCPlugin.spawnEntity} with a caller {@code spawnModel} = the cloned
 *       player skin's {@link Model} ({@code SkinSource.PLAYER_CLONE}) or {@code null} (role default);
 *       {@code preAddToWorld} attaches {@link PerformerIdentityComponent} (+ {@code NonSerialized}
 *       when not persisting); {@code postSpawn} re-applies a fresh {@link PlayerSkinComponent} clone
 *       and SEEDS the leash at the spawn pos (plugin spawns skip the engine leash-seed).
 *       {@code spawnEntity} needs a concrete unlocked {@link Store} (it does not accept a
 *       {@code CommandBuffer}), so this backend uses {@link PerformerSpawnCtx#store()} when a caller
 *       provided one (unlocked, synchronous), else DEFERS the whole spawn to {@code world.execute}
 *       (a lock-held caller had only a {@code CommandBuffer}) - a ONE-TICK spawn latency during
 *       which {@link #isAlive()} is honestly {@code false} and {@link #ref()} {@code null} until the
 *       deferred spawn lands.</li>
 *   <li><b>walkTo</b> - spawn a bare invisible marker at the target, {@link Role#setMarkedTarget}
 *       into the role's {@code MoveTarget} slot (the role's {@code Target} sensor + {@code Seek}
 *       BodyMotion path to it via engine A*), and RE-ANCHOR the leash per destination. Handles the
 *       {@code getRole()}-null-until-first-tick race by retrying the bind on each poll.</li>
 *   <li><b>setProp</b> - {@code InventoryHelper.useItem} into the NPC's own auto-provisioned hotbar
 *       (UNPROVEN render; best-effort).</li>
 *   <li><b>playClip</b> - {@code AnimationUtils.playAnimation} DIRECT (never
 *       {@code NPCEntity.playAnimation}, whose model-registered gate silently swallows custom emote
 *       ids); UNPROVEN coexistence with the walk gait; best-effort.</li>
 * </ul>
 *
 * <p>WORLD-THREAD ONLY (captures the concrete spawn store, either the caller's live store or the
 * fresh one resolved inside the deferred {@code world.execute} hop); every engine call is
 * try-guarded to a no-op, never a throw.
 */
public final class NpcRolePerformer implements StationPerformer {

    /** The marked-target slot the shipped performer role's {@code Target} sensor reads. */
    public static final String MOVE_TARGET_SLOT = "MoveTarget";

    @Nullable
    private Store<EntityStore> store;
    @Nullable
    private NPCEntity npc;
    @Nullable
    private Ref<EntityStore> npcRef;
    @Nullable
    private Ref<EntityStore> markerRef;
    private float spawnYaw;

    @Override
    public void spawn(@Nonnull PerformerSpawnCtx ctx) {
        Store<EntityStore> provided = ctx.store();
        if (provided != null) {
            // Unlocked caller handed a concrete live Store - spawn synchronously.
            doSpawn(provided, ctx);
            return;
        }
        // Only a mutation accessor is present (a lock-held CommandBuffer): NPCPlugin.spawnEntity
        // needs a concrete unlocked Store and cannot run inside the caller's write-processing lock,
        // so DEFER the spawn to the next safe world-thread moment via world.execute. One-tick spawn
        // latency; during the deferred window the NPC ref stays null, so isAlive() is honestly false
        // and ref() null until the spawn lands.
        World world = resolveWorld(ctx);
        if (world == null) {
            warn("spawn skipped: NpcRole has no live Store and no World to defer onto", null);
            return;
        }
        Ref<EntityStore> owner = ctx.ownerRef();
        world.execute(() -> {
            try {
                if (owner == null || !owner.isValid()) {
                    fine("deferred NpcRole spawn: owner ref gone before the deferred hop ran");
                    return;
                }
                Store<EntityStore> fresh = owner.getStore();
                if (fresh == null) {
                    fine("deferred NpcRole spawn: no store resolvable from the owner ref");
                    return;
                }
                doSpawn(fresh, ctx);
            } catch (Throwable t) {
                warn("deferred NpcRole spawn failed: " + t.getMessage(), t);
            }
        });
    }

    /** The world to hop the deferred NPC spawn onto: the ctx world, else resolved off the owner ref. */
    @Nullable
    private static World resolveWorld(@Nonnull PerformerSpawnCtx ctx) {
        World w = ctx.world();
        if (w != null) {
            return w;
        }
        try {
            return WorldEvictors.worldOf(ctx.ownerRef());
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The concrete-{@code Store} spawn body, run either synchronously (an unlocked caller provided
     * {@code ctx.store()}) or from the deferred {@code world.execute} hop (a lock-held caller had
     * only a {@code CommandBuffer}). {@code s} is always an UNLOCKED live store - {@code spawnEntity}
     * mutates it directly.
     */
    private void doSpawn(@Nonnull Store<EntityStore> s, @Nonnull PerformerSpawnCtx ctx) {
        this.store = s;
        PerformerLook look = ctx.look();
        String roleId = look.roleId();
        try {
            if (roleId == null || roleId.isBlank()) {
                warn("spawn skipped: NpcRole look has no RoleId", null);
                return;
            }
            NPCPlugin plugin = NPCPlugin.get();
            if (plugin == null || !plugin.hasRoleName(roleId)) {
                warn("spawn skipped: role '" + roleId + "' not registered", null);
                return;
            }
            int idx = plugin.getIndex(roleId);
            if (idx < 0) {
                warn("spawn skipped: role '" + roleId + "' resolved to a negative index", null);
                return;
            }

            Vector3d spawnPos = new Vector3d(ctx.position());
            this.spawnYaw = ctx.yawRadians();
            Rotation3f spawnRot = new Rotation3f(0f, spawnYaw, 0f);

            // Player-clone appearance: clone the live skin and hand the model to spawnEntity. A
            // RoleDefault look (or a missing owner skin) passes null -> the role's own model.
            PlayerSkin skinClone = null;
            Model model = null;
            if (look.skinSource() == SkinSource.PLAYER_CLONE) {
                PlayerSkinComponent skinComp = s.getComponent(ctx.ownerRef(), PlayerSkinComponent.getComponentType());
                if (skinComp != null) {
                    skinClone = new PlayerSkin(skinComp.getPlayerSkin());
                    model = CosmeticsModule.get().createModel(skinClone);
                }
            }

            PerformerIdentity identity = new PerformerIdentity(ctx.ownerUuid(), ctx.stationKey(),
                    PerformerKind.NPC_ROLE, System.currentTimeMillis());
            boolean persist = look.persist();

            TriConsumer<NPCEntity, Holder<EntityStore>, Store<EntityStore>> preAdd =
                    (npcEntity, holder, st) -> {
                        attachIdentity(holder, identity);
                        if (!persist) {
                            holder.ensureComponent(EntityStore.REGISTRY.getNonSerializedComponentType());
                        }
                    };

            PlayerSkin skinForPost = skinClone;
            Vector3d leashSeed = new Vector3d(spawnPos);
            TriConsumer<NPCEntity, Ref<EntityStore>, Store<EntityStore>> postSpawn =
                    (npcEntity, ref, st) -> {
                        if (skinForPost != null) {
                            st.putComponent(ref, PlayerSkinComponent.getComponentType(),
                                    new PlayerSkinComponent(new PlayerSkin(skinForPost)));
                        }
                        npcEntity.setLeashPoint(new Vector3d(leashSeed));
                        npcEntity.setLeashHeading(spawnYaw);
                        npcEntity.setLeashPitch(0f);
                        this.npc = npcEntity;
                        this.npcRef = ref;
                    };

            var result = plugin.spawnEntity(s, idx, spawnPos, spawnRot, model, preAdd, postSpawn);
            if (result == null || npcRef == null) {
                warn("spawn: spawnEntity returned null for role '" + roleId + "'", null);
                return;
            }

            // Initial prop, if any (best-effort NPC-native hotbar write).
            PropSpec prop = ctx.initialProp();
            if (prop != null && !prop.isEmpty()) {
                setProp(prop);
            }
        } catch (Throwable t) {
            warn("spawn failed: " + t.getMessage(), t);
        }
    }

    private static void attachIdentity(@Nonnull Holder<EntityStore> holder, @Nonnull PerformerIdentity identity) {
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
    public void despawn() {
        Store<EntityStore> s = store;
        if (s != null) {
            removeRef(markerRef, s);
            removeRef(npcRef, s);
        }
        markerRef = null;
        npcRef = null;
        npc = null;
    }

    @Override
    public void presentAt(@Nonnull Vector3d pos, float yaw) {
        Store<EntityStore> s = store;
        Ref<EntityStore> ref = npcRef;
        NPCEntity n = npc;
        if (s == null || ref == null || !ref.isValid()) {
            return;
        }
        try {
            TransformComponent tc = s.getComponent(ref, TransformComponent.getComponentType());
            if (tc != null) {
                tc.getPosition().set(pos.x, pos.y, pos.z);
                tc.getRotation().setYaw(yaw);
            }
            // Re-anchor the leash so background leash/wander logic does not fight the new pose.
            if (n != null) {
                n.setLeashPoint(new Vector3d(pos));
                n.setLeashHeading(yaw);
            }
        } catch (Throwable t) {
            fine("presentAt failed: " + t.getMessage());
        }
    }

    @Override
    @Nonnull
    public WalkHandle walkTo(@Nonnull Vector3d target, double speedMps) {
        Store<EntityStore> s = store;
        Ref<EntityStore> ref = npcRef;
        if (s == null || ref == null || !ref.isValid() || npc == null) {
            return FailedWalkHandle.INSTANCE;
        }
        // Replace any prior marker up front; the handle (re)binds the target, retrying the
        // getRole()-null race on each poll.
        removeRef(markerRef, s);
        markerRef = null;
        return new NpcWalkHandle(new Vector3d(target));
    }

    @Override
    public void setProp(@Nonnull PropSpec prop) {
        Store<EntityStore> s = store;
        Ref<EntityStore> ref = npcRef;
        if (s == null || ref == null || !ref.isValid()) {
            return;
        }
        try {
            String itemId = prop.isEmpty() ? null : prop.itemId();
            InventoryHelper.useItem(ref, itemId, s);
        } catch (Throwable t) {
            fine("setProp failed: " + t.getMessage());
        }
    }

    @Override
    public void playClip(@Nonnull ClipSpec clip) {
        Store<EntityStore> s = store;
        Ref<EntityStore> ref = npcRef;
        if (s == null || ref == null || !ref.isValid()) {
            return;
        }
        try {
            ActiveAnimationComponent anim = s.getComponent(ref, ActiveAnimationComponent.getComponentType());
            if (anim == null) {
                anim = new ActiveAnimationComponent();
                s.putComponent(ref, ActiveAnimationComponent.getComponentType(), anim);
            }
            anim.setPlayingAnimation(clip.slot(), clip.clipId());
            // Direct AnimationUtils, NEVER NPCEntity.playAnimation (the Emote-gate landmine).
            AnimationUtils.playAnimation(ref, clip.slot(), clip.itemAnimationsId(), clip.clipId(),
                    clip.sendToSelf(), s);
        } catch (Throwable t) {
            fine("playClip failed: " + t.getMessage());
        }
    }

    @Override
    public boolean isAlive() {
        return npcRef != null && npcRef.isValid();
    }

    @Override
    @Nullable
    public Ref<EntityStore> ref() {
        return npcRef;
    }

    /** Spawns a bare, invisible (no ModelComponent), NonSerialized marker at {@code pos}. */
    @Nullable
    private static Ref<EntityStore> spawnMarker(@Nonnull Store<EntityStore> store, @Nonnull Vector3d pos) {
        try {
            Rotation3f rot = new Rotation3f(0f, 0f, 0f);
            Holder<EntityStore> holder = EntityStore.REGISTRY.newHolder();
            holder.addComponent(TransformComponent.getComponentType(), new TransformComponent(pos, rot));
            holder.ensureComponent(UUIDComponent.getComponentType());
            holder.addComponent(NetworkId.getComponentType(), new NetworkId(store.getExternalData().takeNextNetworkId()));
            holder.ensureComponent(EntityStore.REGISTRY.getNonSerializedComponentType());
            return store.addEntity(holder, AddReason.SPAWN);
        } catch (Throwable t) {
            fine("spawnMarker failed: " + t.getMessage());
            return null;
        }
    }

    private static void removeRef(@Nullable Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        if (ref == null || !ref.isValid()) {
            return;
        }
        try {
            store.removeEntity(ref, RemoveReason.REMOVE);
        } catch (Throwable t) {
            fine("removeRef failed: " + t.getMessage());
        }
    }

    /**
     * Measures distance to a marked target the engine's A* drives (the NPC's own motion controller
     * moves it; we only poll). Binds the target lazily on the first poll where {@code getRole()} is
     * non-null (retrying the null-until-first-tick race), then reads arrival / stuck.
     */
    private final class NpcWalkHandle implements WalkHandle {
        @Nonnull
        private final Vector3d target;
        private boolean bound;
        @Nonnull
        private State state = State.WALKING;
        private double lastDist = Double.MAX_VALUE;
        private long stalledMs;

        NpcWalkHandle(@Nonnull Vector3d target) {
            this.target = target;
        }

        @Override
        @Nonnull
        public State poll(double dtMs) {
            if (state != State.WALKING) {
                return state;
            }
            Store<EntityStore> s = store;
            Ref<EntityStore> ref = npcRef;
            NPCEntity n = npc;
            if (s == null || ref == null || !ref.isValid() || n == null) {
                return finish(State.FAILED);
            }
            if (!bound) {
                tryBind(s, n);
                // Not yet bound (role brain not ticked): stay WALKING and retry next poll.
                if (!bound) {
                    return state;
                }
            }

            TransformComponent tc = s.getComponent(ref, TransformComponent.getComponentType());
            if (tc == null) {
                return finish(State.FAILED);
            }
            Vector3d pos = tc.getPosition();
            double dist = PerformerWalkMath.horizontalDistance(pos.x, pos.z, target.x, target.z);
            if (PerformerWalkMath.arrived(dist, PerformerWalkMath.ARRIVE_RADIUS)) {
                return finish(State.ARRIVED);
            }
            double moved = Math.abs(lastDist - dist);
            lastDist = dist;
            if (moved < PerformerWalkMath.STUCK_EPS) {
                stalledMs += (long) dtMs;
            } else {
                stalledMs = 0L;
            }
            if (PerformerWalkMath.stuck(moved, PerformerWalkMath.STUCK_EPS, stalledMs, PerformerWalkMath.STUCK_WINDOW_MS)) {
                // A lenient near-miss counts as arrived; a true stall gives up.
                return finish(PerformerWalkMath.nearMiss(dist, PerformerWalkMath.NEAR_MISS_RADIUS)
                        ? State.ARRIVED : State.STUCK);
            }
            return state;
        }

        /**
         * Terminal transition: latch {@code terminal} AND strip the invisible {@code MoveTarget}
         * marker the walk spawned (it exists only to be pathed toward, so it must not outlive the
         * walk). No-op on the marker when the walk never bound one. Without this an ARRIVED walk
         * stranded its marker until the NEXT {@code walkTo} / {@code despawn} / {@code cancel}, and
         * the marker carries no {@link PerformerIdentityComponent} so {@code PerformerReconciler}
         * never catches it.
         */
        @Nonnull
        private State finish(@Nonnull State terminal) {
            this.state = terminal;
            clearMarker();
            return terminal;
        }

        /** Remove the invisible MoveTarget marker if one is live (no-op when never spawned). */
        private void clearMarker() {
            Store<EntityStore> s = store;
            if (s != null) {
                removeRef(markerRef, s);
            }
            markerRef = null;
        }

        private void tryBind(@Nonnull Store<EntityStore> s, @Nonnull NPCEntity n) {
            Role role = n.getRole();
            if (role == null) {
                return;
            }
            try {
                Ref<EntityStore> marker = spawnMarker(s, target);
                if (marker == null) {
                    state = State.FAILED;
                    return;
                }
                markerRef = marker;
                role.setMarkedTarget(MOVE_TARGET_SLOT, marker);
                n.setLeashPoint(new Vector3d(target));
                bound = true;
            } catch (Throwable t) {
                fine("walk bind failed: " + t.getMessage());
                state = State.FAILED;
            }
        }

        @Override
        @Nonnull
        public State state() {
            return state;
        }

        @Override
        public void cancel() {
            if (state == State.WALKING) {
                state = State.FAILED;
            }
            clearMarker();
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
