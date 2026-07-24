package com.ziggfreed.common.entity.performer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The ONE internal seam a caller drives to present a "double performing the work" - both backends
 * ({@link HolderPerformer} = the bare-{@code Holder} skinned puppet, {@link NpcRolePerformer} = a
 * Role-driven {@code NPCEntity}) implement it, so a station-puppet controller drives ONE object and
 * never branches on the {@link PerformerLook.LookSource look source}. A performer is STATEFUL: it
 * owns its own spawned ref and captures the {@link PerformerSpawnCtx#world() world}/
 * {@link PerformerSpawnCtx#store() store} at {@link #spawn}, reusing them for every later method.
 *
 * <p><b>Hide is deliberately NOT on this interface.</b> Hiding the real player is orthogonal to how
 * the double is rendered - it acts on the PLAYER (via {@code PlayerPuppetService.hideByScale},
 * session-scoped, set once at engage) and stays owned by the caller regardless of backend.
 *
 * <p><b>Graceful capability envelope.</b> The caller calls {@link #setProp}/{@link #playClip}
 * UNCONDITIONALLY; each backend's method body decides what it can do (the NPC backend's prop/clip
 * are best-effort until proven, per the seam design) without the caller knowing which backend it
 * holds. Every method is WORLD-THREAD ONLY and try-guarded to a no-op, never a throw into the caller.
 */
public interface StationPerformer {

    /** Create the visible double at the anchor and apply the {@link PerformerLook}. */
    void spawn(@Nonnull PerformerSpawnCtx ctx);

    /** Tear the double down. Idempotent; safe to call twice (a no-op once already gone). */
    void despawn();

    /** Place / re-anchor the double at {@code pos} facing {@code yaw} (a teleport-set). */
    void presentAt(@Nonnull Vector3d pos, float yaw);

    /**
     * Start moving the double toward {@code target} at {@code speedMps}, returning a poll-driven
     * {@link WalkHandle}. Never returns {@code null} (an unstartable walk yields a handle already in
     * {@link WalkHandle.State#FAILED}).
     */
    @Nonnull
    WalkHandle walkTo(@Nonnull Vector3d target, double speedMps);

    /** Set / swap / clear the held prop. */
    void setProp(@Nonnull PropSpec prop);

    /** Fire a one-shot work animation. */
    void playClip(@Nonnull ClipSpec clip);

    /** Whether the double's ref is still valid (spawned and not yet despawned/lost). */
    boolean isAlive();

    /** The spawned double's entity ref, or {@code null} before {@link #spawn} / after teardown. */
    @Nullable
    Ref<EntityStore> ref();
}
