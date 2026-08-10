package com.ziggfreed.common.effect;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The companion "remove everything this session applied" bookkeeping primitive for
 * {@link NativeEffectUtil}: record every {@code (ref, effectId)} pair a session successfully
 * applies via {@link #track}, then {@link #removeAll} strips them all in one call at session
 * stop - the shape a station/moment/cast SESSION needs (apply effects across its lifetime at
 * various points, guarantee every one of them is gone when the session ends, regardless of how
 * many were applied or to how many different targets).
 *
 * <p>Deliberately dumb: this class does NOT call {@link NativeEffectUtil#apply} itself (a caller
 * applies the effect its own way - {@code apply} or {@code applyFor}, whichever engine overload it
 * needs - and calls {@link #track} only once that apply reports success, so a failed apply is
 * never tracked and never leaves a phantom remove attempt at stop). {@link #removeAll} is
 * best-effort PER ENTRY: one entry's remove failing (already gone, ref invalidated, engine throw -
 * see {@link NativeEffectUtil#remove}'s own fail-closed contract) never stops the rest, and the
 * tracked list is cleared regardless of how many removes actually succeeded, so a session can
 * never re-track/re-attempt a stale entry on a second {@code removeAll} call.
 *
 * <p>Ordinary mutable list, NOT thread-safe - matches the primitive's ownership shape (one
 * instance per session, touched only from the world thread that owns that session, mirroring
 * {@code EntityEffectService}'s own "caller tracks state, service holds none" split). Multiple
 * different targets in one session are supported (each tracked entry carries its own ref); nothing
 * assumes a single fixed target.
 */
public final class AppliedEffectTracker {

    private final List<Applied> applied = new ArrayList<>();

    /**
     * Record that {@code effectId} was successfully applied to {@code ref}. Call this ONLY after
     * the corresponding {@link NativeEffectUtil#apply}/{@code applyFor} call returned {@code true} -
     * this method itself does not verify or re-apply anything.
     */
    public void track(@Nonnull Ref<EntityStore> ref, @Nonnull String effectId) {
        applied.add(new Applied(ref, effectId));
    }

    /** {@code true} when nothing is currently tracked (a fresh tracker, or right after
     *  {@link #removeAll}). */
    public boolean isEmpty() {
        return applied.isEmpty();
    }

    /** The number of currently tracked {@code (ref, effectId)} entries. */
    public int size() {
        return applied.size();
    }

    /**
     * Remove every tracked effect ({@link Store} form) via {@link NativeEffectUtil#remove}, then
     * clear the tracked list unconditionally (best-effort: a per-entry remove failure never stops
     * the rest, and is never re-attempted on a later call).
     */
    public void removeAll(@Nonnull Store<EntityStore> store) {
        for (Applied a : applied) {
            NativeEffectUtil.remove(store, a.ref(), a.effectId());
        }
        applied.clear();
    }

    /** {@link ComponentAccessor} form of {@link #removeAll(Store)} (also fits a
     *  {@code CommandBuffer}). */
    public void removeAll(@Nonnull ComponentAccessor<EntityStore> accessor) {
        for (Applied a : applied) {
            NativeEffectUtil.remove(accessor, a.ref(), a.effectId());
        }
        applied.clear();
    }

    private record Applied(@Nonnull Ref<EntityStore> ref, @Nonnull String effectId) {
    }
}
