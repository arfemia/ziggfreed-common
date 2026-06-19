package com.ziggfreed.common.instance.effect;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.OverlapBehavior;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Generic, mod-agnostic applier for Hytale {@link EntityEffect}s on an entity. Lifts the
 * two patterns that recur across minigames into one config-free seam:
 *
 * <ol>
 *   <li><b>Timed apply</b> ({@link #applyTimed}): resolve an effect id to its asset, fetch
 *       the entity's {@link EffectControllerComponent}, and add the effect for a duration
 *       under a chosen {@link OverlapBehavior} - the "stun visual" shape
 *       ({@code ctrl.addEffect(ref, fx, durationSec, OverlapBehavior.OVERWRITE, accessor)}).</li>
 *   <li><b>Idempotent band swap</b> ({@link #applyBand}): given an {@link EffectBandLadder}
 *       and the effect index CURRENTLY applied to this entity, remove the old one and add the
 *       new band's effect, returning the new applied index for the CALLER to track per-entity
 *       (the "pace band" shape, where the consumer holds the applied state, not this service).</li>
 * </ol>
 *
 * <p><b>World-thread only.</b> Every method reads an {@link EffectControllerComponent} off the
 * accessor and mutates it, so the CALLER must invoke these on the world thread (inside the
 * system tick or a {@code world.execute(...)} hop). The service never hops internally.
 *
 * <p>Every engine touch is try-guarded: a not-yet-registered effect id, a bad ref, or an entity
 * with no controller degrades to a no-op (a {@code false} / unchanged-index return), never a
 * throw into the caller. Effect ids are resolved per call (no caching here); a consumer that
 * wants caching wraps an {@code AssetIndexCache}. No logging on these paths (pure engine glue
 * reachable from hot per-tick loops, and the raw flogger LOGGER throws in a log-manager-less
 * unit JVM).
 */
public final class EntityEffectService {

    /** Engine sentinel for "no such effect / none applied" ({@code Integer.MIN_VALUE}). */
    public static final int NONE = Integer.MIN_VALUE;

    private EntityEffectService() {
    }

    // --- timed apply (the stun-visual shape) ---

    /**
     * Apply {@code effectId} to {@code ref} for {@code durationSeconds} under {@code overlap},
     * via a {@link CommandBuffer} (the inside-a-damage/event-system path). World-thread only;
     * best-effort.
     *
     * @param ref             the entity to affect
     * @param effectId        the {@link EntityEffect} asset id
     * @param durationSeconds effect duration in seconds (clamped to {@code >= 0})
     * @param overlap         how a re-apply combines with a live instance of the same effect
     * @param cb              the command buffer (also the {@link EffectControllerComponent} source)
     * @return true if the effect resolved and was added, false on any miss (unregistered id,
     *         bad ref, no controller, or an engine throw)
     */
    public static boolean applyTimed(@Nonnull Ref<EntityStore> ref, @Nonnull String effectId,
                                     float durationSeconds, @Nonnull OverlapBehavior overlap,
                                     @Nonnull CommandBuffer<EntityStore> cb) {
        return applyTimedInternal(ref, effectId, durationSeconds, overlap, cb);
    }

    /**
     * Apply {@code effectId} to {@code ref} for {@code durationSeconds} under {@code overlap},
     * via a {@link Store} (the inside-a-tick / direct-store path). World-thread only;
     * best-effort. Mirrors {@link #applyTimed(Ref, String, float, OverlapBehavior, CommandBuffer)}.
     *
     * @return true if the effect resolved and was added, false on any miss
     */
    public static boolean applyTimed(@Nonnull Ref<EntityStore> ref, @Nonnull String effectId,
                                     float durationSeconds, @Nonnull OverlapBehavior overlap,
                                     @Nonnull Store<EntityStore> store) {
        return applyTimedInternal(ref, effectId, durationSeconds, overlap, store);
    }

    /**
     * Shared timed-apply over the common {@link ComponentAccessor} supertype (both
     * {@link Store} and {@link CommandBuffer} implement it, and {@code addEffect} itself takes
     * an accessor), so the two public overloads are one line each. Best-effort, try-guarded.
     */
    private static boolean applyTimedInternal(@Nonnull Ref<EntityStore> ref, @Nonnull String effectId,
                                              float durationSeconds, @Nonnull OverlapBehavior overlap,
                                              @Nonnull ComponentAccessor<EntityStore> accessor) {
        if (ref == null || !ref.isValid() || effectId == null || effectId.isBlank()) {
            return false;
        }
        try {
            EntityEffect fx = resolveEffect(effectId);
            if (fx == null) {
                return false;
            }
            EffectControllerComponent ctrl =
                    accessor.getComponent(ref, EffectControllerComponent.getComponentType());
            if (ctrl == null) {
                return false;
            }
            return ctrl.addEffect(ref, fx, Math.max(0f, durationSeconds), overlap, accessor);
        } catch (Throwable ignored) {
            // unregistered effect / bad ref / engine throw -> no-op
            return false;
        }
    }

    // --- idempotent band swap (the pace-band shape) ---

    /**
     * Swap the entity's band effect to {@code ladder}'s {@code targetBandIndex} rung: remove the
     * effect index the caller says is currently applied ({@code currentlyAppliedEffectIndex}, when
     * it is not {@link #NONE}) and add the target band's effect (when that band carries one). The
     * service holds NO per-entity state; the CALLER tracks the returned index (as a
     * {@code HunterUnit}-style field) and passes it back next swap. World-thread only; best-effort.
     *
     * <p>Note the caller is expected to only call this when the band actually CHANGED (the common
     * "band == appliedBand -> skip" guard stays in the caller, avoiding per-tick effect churn);
     * this method does the remove-old + add-new regardless.
     *
     * @param ref                          the entity to affect
     * @param ladder                       the band ladder owning the effect ids
     * @param targetBandIndex              the band to move to (the new effect, or baseline)
     * @param currentlyAppliedEffectIndex  the effect index currently applied to this entity, or
     *                                     {@link #NONE} if none (the caller's tracked state)
     * @param store                        the store (component source + effect accessor)
     * @return the new applied effect index to track, or {@link #NONE} if the target band is a
     *         baseline / clear rung, the add failed, or anything degraded. On a hard miss (bad ref,
     *         no controller) the OLD effect could not be removed either, so {@link #NONE} is returned
     *         and the caller should treat the entity as cleared.
     */
    public static int applyBand(@Nonnull Ref<EntityStore> ref, @Nonnull EffectBandLadder ladder,
                                int targetBandIndex, int currentlyAppliedEffectIndex,
                                @Nonnull Store<EntityStore> store) {
        if (ref == null || !ref.isValid() || ladder == null) {
            return NONE;
        }
        EffectControllerComponent ctrl;
        try {
            ctrl = store.getComponent(ref, EffectControllerComponent.getComponentType());
        } catch (Throwable ignored) {
            return NONE;
        }
        if (ctrl == null) {
            return NONE;
        }

        // Remove the old band effect first (idempotent: NONE means nothing applied).
        if (currentlyAppliedEffectIndex != NONE) {
            try {
                ctrl.removeEffect(ref, currentlyAppliedEffectIndex, store);
            } catch (Throwable ignored) {
                // already gone / unknown index -> nothing to remove
            }
        }

        // Resolve + add the new band effect, if this band carries one.
        String newId = ladder.effectIdFor(targetBandIndex);
        if (newId == null || newId.isBlank()) {
            return NONE; // baseline / clear rung
        }
        try {
            int newIndex = EntityEffect.getAssetMap().getIndex(newId);
            if (newIndex == Integer.MIN_VALUE) {
                return NONE;
            }
            EntityEffect fx = EntityEffect.getAssetMap().getAsset(newIndex);
            if (fx == null) {
                return NONE;
            }
            boolean added = ctrl.addEffect(ref, fx, store);
            return added ? newIndex : NONE;
        } catch (Throwable ignored) {
            return NONE;
        }
    }

    // --- shared resolution ---

    /**
     * Resolve an {@link EntityEffect} asset id to its asset, or {@code null} when the id is not
     * registered yet / the asset map is not ready. Try-guarded (the map throws before it is loaded).
     */
    @javax.annotation.Nullable
    private static EntityEffect resolveEffect(@Nonnull String effectId) {
        try {
            int idx = EntityEffect.getAssetMap().getIndex(effectId);
            if (idx == Integer.MIN_VALUE) {
                return null;
            }
            return EntityEffect.getAssetMap().getAsset(idx);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
