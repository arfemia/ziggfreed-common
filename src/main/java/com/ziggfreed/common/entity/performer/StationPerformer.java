package com.ziggfreed.common.entity.performer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The ONE internal seam a caller drives to present a "double performing the work" - both backends
 * ({@link HolderPerformer} = the bare-{@code Holder} skinned puppet, {@link NpcRolePerformer} = a
 * Role-driven {@code NPCEntity}) implement it, so a station-puppet controller drives ONE object and
 * never branches on the {@link PerformerLook.LookSource look source}. A performer is STATEFUL: it
 * owns its own spawned ref (and, backend-permitting, a captured spawn store).
 *
 * <p><b>Per-call mutation accessor (decision 55, 2026-07-24).</b> Every MUTATING method takes a
 * fresh per-call {@link ComponentAccessor}{@code <EntityStore>} - a {@link com.hypixel.hytale.component.Store}
 * or a {@link com.hypixel.hytale.component.CommandBuffer}, whichever the CALLER'S current frame
 * legally holds. A {@code CommandBuffer} is valid ONLY for its own processing pass and cannot be
 * captured across frames, so a station-puppet controller driving mutations from successive
 * processing-locked frames (the {@code toggle()} interaction handler, the heartbeat frame drain)
 * MUST thread a fresh accessor per call, exactly as the shipped {@code StationPuppetController}
 * pattern proves. {@link #isAlive()}/{@link #ref()} stay read-only and param-less.
 * {@link #spawn(PerformerSpawnCtx)} carries its accessor on {@link PerformerSpawnCtx#accessor()}.
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

    /**
     * Tear the double down through the caller frame's {@code accessor}. Idempotent; safe to call
     * twice (a no-op once already gone).
     */
    void despawn(@Nonnull ComponentAccessor<EntityStore> accessor);

    /** Place / re-anchor the double at {@code pos} facing {@code yaw} (a teleport-set). */
    void presentAt(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Vector3d pos, float yaw);

    /**
     * {@link #presentAt(ComponentAccessor, Vector3d, float)} with an explicit {@code pitch}/
     * {@code roll} (radians). All three angles are the puppet's OWN authored tilt - a caller
     * composing any block-facing yaw resolves it before calling, same convention as the yaw-only
     * overload. The default implementation delegates to the yaw-only overload (pitch/roll dropped)
     * so an implementor that has not opted into the full rotation still compiles and behaves
     * exactly as before; {@link HolderPerformer} and {@link NpcRolePerformer} override this to
     * apply the full rotation to their transform (and head rotation, where present).
     */
    default void presentAt(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Vector3d pos, float yaw,
            float pitch, float roll) {
        presentAt(accessor, pos, yaw);
    }

    /**
     * Start moving the double toward {@code target} at {@code speedMps}, returning a poll-driven
     * {@link WalkHandle}. Never returns {@code null} (an unstartable walk yields a handle already in
     * {@link WalkHandle.State#FAILED}). {@code accessor} is the STARTING frame's accessor (path
     * solve + initial walk-state write); each subsequent {@link WalkHandle#poll} takes its OWN
     * frame's accessor.
     */
    @Nonnull
    WalkHandle walkTo(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Vector3d target, double speedMps);

    /** Set / swap / clear the held prop through the caller frame's {@code accessor}. */
    void setProp(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull PropSpec prop);

    /** Fire a one-shot work animation through the caller frame's {@code accessor}. */
    void playClip(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull ClipSpec clip);

    /** Whether the double's ref is still valid (spawned and not yet despawned/lost). */
    boolean isAlive();

    /** The spawned double's entity ref, or {@code null} before {@link #spawn} / after teardown. */
    @Nullable
    Ref<EntityStore> ref();
}
