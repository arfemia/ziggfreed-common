package com.ziggfreed.common.effect;

import java.util.Collections;
import java.util.Set;

import javax.annotation.Nonnull;

import com.hypixel.hytale.assetstore.map.CaseInsensitiveHashStrategy;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.OverlapBehavior;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.CommonLog;

import it.unimi.dsi.fastutil.objects.ObjectOpenCustomHashSet;

/**
 * Apply / remove a Hytale {@code EntityEffect} asset on an entity by id, over the two
 * {@code EffectControllerComponent.addEffect} shapes a consumer composing native effect content by
 * REFERENCE needs (per the id-ref-only content-composition principle: our own vocabularies
 * reference native asset ids, never inline a native body):
 *
 * <ul>
 *   <li>{@link #apply(Store, Ref, String)} / {@link #apply(ComponentAccessor, Ref, String)} - the
 *       ASSET-AUTHORITATIVE 3-arg engine overload ({@code addEffect(ref, entityEffect,
 *       accessor)}): duration, {@code OverlapBehavior}, and the infinite flag all come from the
 *       effect ASSET itself. Use this when the asset already authors the right lifetime (a
 *       one-shot on-hit effect, an authored-infinite aura).</li>
 *   <li>{@link #applyFor(Store, Ref, String, float, OverlapBehavior)} /
 *       {@link #applyFor(ComponentAccessor, Ref, String, float, OverlapBehavior)} - the
 *       DURATION-OVERRIDE 5-arg engine overload ({@code addEffect(ref, entityEffect, duration,
 *       overlapBehavior, accessor)}): reuses one effect asset's other fields (tint/particles/
 *       movement-lock/ability-disable) at a CALLER-CHOSEN duration + overlap, so a session/step
 *       can author its own lifetime without a duplicate effect asset per duration.</li>
 *   <li>{@link #applyInfinite(Store, Ref, String)} / {@link #applyInfinite(ComponentAccessor, Ref, String)}
 *       - the NO-EXPIRY engine overload ({@code addInfiniteEffect(ref, index, entityEffect,
 *       accessor)}): the effect stays on until removed, whatever its asset authors. For an entity
 *       with no {@code EntityStatMap} (a puppet, a prop), where the engine's effect timer never
 *       runs and a timed apply would never end; pair it with {@code remove} on the caller's own
 *       clock.</li>
 *   <li>{@link #remove(Store, Ref, String)} / {@link #remove(ComponentAccessor, Ref, String)} -
 *       resolves {@code effectId} to its current engine index and calls
 *       {@code EffectControllerComponent.removeEffect}. Safe to call for an effect that is not
 *       currently applied (the engine no-ops on an unknown/absent index); this util additionally
 *       degrades to {@code false} rather than throw on a bad ref, a missing controller, an
 *       unresolved id, or an engine throw.</li>
 *   <li>{@link #has(Store, Ref, String)} / {@link #has(ComponentAccessor, Ref, String)} - whether
 *       the entity has the effect active right now ({@code EffectControllerComponent.hasEffect} on
 *       the id's current engine index); false whenever it cannot tell, so a caller reconciling a
 *       wanted effect asks this and applies on false.</li>
 * </ul>
 *
 * <p><b>Fail-closed throughout.</b> A {@code null}/blank id, an invalid ref, a target with no
 * {@link EffectControllerComponent}, an unregistered effect id, or any engine throw all degrade to
 * {@code false} - never a throw into the caller, and never a silent success. Every miss logs at
 * most once per call (guarded FINE for a missing-component/invalid-ref no-op, guarded WARN for an
 * engine throw; {@code has} logs every miss at FINE, since the apply it precedes is the call that
 * names the id) so a caller only needs the boolean. An UNRESOLVED id is named at WARN once per id
 * per process, whichever call meets it first, and at FINE after that: a caller reconciling on
 * every change (the gear-set engine) would otherwise repeat the same line on each one. That latch is
 * keyed exactly as the engine's {@code EntityEffect} asset map resolves an id (its own
 * {@code CaseInsensitiveHashStrategy}), so two spellings the map treats as one id warn once.
 *
 * <p><b>World-thread only</b> (reads/mutates an {@link EffectControllerComponent}); the caller
 * guarantees the thread. See {@link AppliedEffectTracker} for the companion "track what this
 * session applied, remove it all at stop" helper.
 */
public final class NativeEffectUtil {

    private NativeEffectUtil() {
    }

    // --- asset-authoritative apply (3-arg: duration/overlap/infinite all from the asset) ---

    /** {@link Store} form of the asset-authoritative apply. */
    public static boolean apply(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                                @Nonnull String effectId) {
        return applyInternal(store, ref, effectId);
    }

    /** {@link ComponentAccessor} form of the asset-authoritative apply (also fits a
     *  {@code CommandBuffer}, which implements the same supertype). */
    public static boolean apply(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref,
                                @Nonnull String effectId) {
        return applyInternal(accessor, ref, effectId);
    }

    private static boolean applyInternal(@Nonnull ComponentAccessor<EntityStore> accessor,
                                         @Nonnull Ref<EntityStore> ref, @Nonnull String effectId) {
        if (ref == null || !ref.isValid() || effectId == null || effectId.isBlank()) {
            fine("apply: invalid ref or blank id (" + effectId + ")");
            return false;
        }
        try {
            EntityEffect fx = resolveAsset(effectId);
            if (fx == null) {
                warnUnresolvedOnce("apply", effectId);
                return false;
            }
            EffectControllerComponent ctrl = accessor.getComponent(ref, EffectControllerComponent.getComponentType());
            if (ctrl == null) {
                fine("apply: entity has no EffectControllerComponent - skipping " + effectId);
                return false;
            }
            return ctrl.addEffect(ref, fx, accessor);
        } catch (Throwable t) {
            warn("apply: '" + effectId + "' failed: " + t.getMessage());
            return false;
        }
    }

    // --- duration-override apply (5-arg: caller picks duration + overlap) ---

    /** {@link Store} form of the duration-override apply. */
    public static boolean applyFor(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                                   @Nonnull String effectId, float durationSeconds,
                                   @Nonnull OverlapBehavior overlap) {
        return applyForInternal(store, ref, effectId, durationSeconds, overlap);
    }

    /** {@link ComponentAccessor} form of the duration-override apply (also fits a
     *  {@code CommandBuffer}). */
    public static boolean applyFor(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref,
                                   @Nonnull String effectId, float durationSeconds,
                                   @Nonnull OverlapBehavior overlap) {
        return applyForInternal(accessor, ref, effectId, durationSeconds, overlap);
    }

    private static boolean applyForInternal(@Nonnull ComponentAccessor<EntityStore> accessor,
                                            @Nonnull Ref<EntityStore> ref, @Nonnull String effectId,
                                            float durationSeconds, @Nonnull OverlapBehavior overlap) {
        if (ref == null || !ref.isValid() || effectId == null || effectId.isBlank()) {
            fine("applyFor: invalid ref or blank id (" + effectId + ")");
            return false;
        }
        try {
            EntityEffect fx = resolveAsset(effectId);
            if (fx == null) {
                warnUnresolvedOnce("applyFor", effectId);
                return false;
            }
            EffectControllerComponent ctrl = accessor.getComponent(ref, EffectControllerComponent.getComponentType());
            if (ctrl == null) {
                fine("applyFor: entity has no EffectControllerComponent - skipping " + effectId);
                return false;
            }
            return ctrl.addEffect(ref, fx, Math.max(0f, durationSeconds), overlap, accessor);
        } catch (Throwable t) {
            warn("applyFor: '" + effectId + "' failed: " + t.getMessage());
            return false;
        }
    }

    // --- infinite apply (the engine's own addInfiniteEffect: held until removed) ---

    /** {@link Store} form of the infinite apply. */
    public static boolean applyInfinite(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                                        @Nonnull String effectId) {
        return applyInfiniteInternal(store, ref, effectId);
    }

    /** {@link ComponentAccessor} form of the infinite apply (also fits a {@code CommandBuffer}). */
    public static boolean applyInfinite(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref,
                                        @Nonnull String effectId) {
        return applyInfiniteInternal(accessor, ref, effectId);
    }

    /**
     * Applies {@code effectId} with NO expiry, through the engine's own
     * {@code EffectControllerComponent.addInfiniteEffect(ref, index, effect, accessor)}: the effect
     * stays on until a {@link #remove} takes it off, whatever duration its asset authors. The shape
     * for an entity that carries no {@code EntityStatMap} (a spawned puppet, a prop), on which the
     * engine's effect timer never runs, so a timed apply would never end there: apply it infinite and
     * remove it on the caller's own clock. Same guards and return contract as {@link #apply}.
     */
    private static boolean applyInfiniteInternal(@Nonnull ComponentAccessor<EntityStore> accessor,
                                                 @Nonnull Ref<EntityStore> ref, @Nonnull String effectId) {
        if (ref == null || !ref.isValid() || effectId == null || effectId.isBlank()) {
            fine("applyInfinite: invalid ref or blank id (" + effectId + ")");
            return false;
        }
        try {
            int idx = EntityEffect.getAssetMap().getIndex(effectId);
            if (idx == Integer.MIN_VALUE) {
                warnUnresolvedOnce("applyInfinite", effectId);
                return false;
            }
            EntityEffect fx = EntityEffect.getAssetMap().getAsset(idx);
            if (fx == null) {
                warnUnresolvedOnce("applyInfinite", effectId);
                return false;
            }
            EffectControllerComponent ctrl = accessor.getComponent(ref, EffectControllerComponent.getComponentType());
            if (ctrl == null) {
                fine("applyInfinite: entity has no EffectControllerComponent - skipping " + effectId);
                return false;
            }
            return ctrl.addInfiniteEffect(ref, idx, fx, accessor);
        } catch (Throwable t) {
            warn("applyInfinite: '" + effectId + "' failed: " + t.getMessage());
            return false;
        }
    }

    // --- remove by id (resolves the current engine index) ---

    /** {@link Store} form of remove-by-id. */
    public static boolean remove(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                                 @Nonnull String effectId) {
        return removeInternal(store, ref, effectId);
    }

    /** {@link ComponentAccessor} form of remove-by-id (also fits a {@code CommandBuffer}). */
    public static boolean remove(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref,
                                 @Nonnull String effectId) {
        return removeInternal(accessor, ref, effectId);
    }

    private static boolean removeInternal(@Nonnull ComponentAccessor<EntityStore> accessor,
                                          @Nonnull Ref<EntityStore> ref, @Nonnull String effectId) {
        if (ref == null || !ref.isValid() || effectId == null || effectId.isBlank()) {
            fine("remove: invalid ref or blank id (" + effectId + ")");
            return false;
        }
        try {
            int idx = EntityEffect.getAssetMap().getIndex(effectId);
            if (idx == Integer.MIN_VALUE) {
                warnUnresolvedOnce("remove", effectId);
                return false;
            }
            EffectControllerComponent ctrl = accessor.getComponent(ref, EffectControllerComponent.getComponentType());
            if (ctrl == null) {
                fine("remove: entity has no EffectControllerComponent - skipping " + effectId);
                return false;
            }
            ctrl.removeEffect(ref, idx, accessor);
            return true;
        } catch (Throwable t) {
            warn("remove: '" + effectId + "' failed: " + t.getMessage());
            return false;
        }
    }

    // --- has by id (resolves the current engine index) ---

    /** {@link Store} form of has-by-id. */
    public static boolean has(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                              @Nonnull String effectId) {
        return hasInternal(store, ref, effectId);
    }

    /** {@link ComponentAccessor} form of has-by-id (also fits a {@code CommandBuffer}). */
    public static boolean has(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref,
                              @Nonnull String effectId) {
        return hasInternal(accessor, ref, effectId);
    }

    /**
     * Whether the entity has {@code effectId} active right now, through
     * {@code EffectControllerComponent.hasEffect(int)} on the id's current engine index. False
     * whenever it cannot tell (a bad ref, a blank or unregistered id, no controller, an engine
     * throw), each logged at FINE only: a caller asks this before an {@code apply}, and the apply
     * is the call that names an unregistered id out loud.
     */
    private static boolean hasInternal(@Nonnull ComponentAccessor<EntityStore> accessor,
                                       @Nonnull Ref<EntityStore> ref, @Nonnull String effectId) {
        if (ref == null || !ref.isValid() || effectId == null || effectId.isBlank()) {
            fine("has: invalid ref or blank id (" + effectId + ")");
            return false;
        }
        try {
            int idx = EntityEffect.getAssetMap().getIndex(effectId);
            if (idx == Integer.MIN_VALUE) {
                fine("has: EntityEffect '" + effectId + "' not found in asset map");
                return false;
            }
            EffectControllerComponent ctrl = accessor.getComponent(ref, EffectControllerComponent.getComponentType());
            if (ctrl == null) {
                fine("has: entity has no EffectControllerComponent - " + effectId);
                return false;
            }
            return ctrl.hasEffect(idx);
        } catch (Throwable t) {
            fine("has: '" + effectId + "' failed: " + t.getMessage());
            return false;
        }
    }

    // --- shared resolution ---

    @javax.annotation.Nullable
    private static EntityEffect resolveAsset(@Nonnull String effectId) {
        int idx = EntityEffect.getAssetMap().getIndex(effectId);
        if (idx == Integer.MIN_VALUE) {
            return null;
        }
        return EntityEffect.getAssetMap().getAsset(idx);
    }

    /**
     * The ids already named out loud as unresolved; see {@link #warnUnresolvedOnce}. Keyed by the
     * asset map's own case-insensitive strategy, so the latch and the lookup agree on what one id is.
     */
    private static final Set<String> WARNED_UNRESOLVED = Collections.synchronizedSet(
            new ObjectOpenCustomHashSet<>(CaseInsensitiveHashStrategy.<String>getInstance()));

    /**
     * Name an unresolved {@code effectId} at WARN the first time any call meets it, at FINE after.
     *
     * @return true when this call is the one that warned
     */
    static boolean warnUnresolvedOnce(@Nonnull String op, @Nonnull String effectId) {
        String message = op + ": EntityEffect '" + effectId + "' not found in asset map";
        if (WARNED_UNRESOLVED.add(effectId)) {
            warn(message);
            return true;
        }
        fine(message);
        return false;
    }

    private static void warn(@Nonnull String message) {
        try {
            CommonLog.LOGGER.atWarning().log("[ziggfreed-common][effect] " + message);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM: the flogger LOGGER can throw; swallow it.
        }
    }

    private static void fine(@Nonnull String message) {
        try {
            CommonLog.LOGGER.atFine().log("[ziggfreed-common][effect] " + message);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM: the flogger LOGGER can throw; swallow it.
        }
    }
}
