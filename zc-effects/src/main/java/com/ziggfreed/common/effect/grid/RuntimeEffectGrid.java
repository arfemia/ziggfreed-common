package com.ziggfreed.common.effect.grid;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.RawAsset;
import com.hypixel.hytale.assetstore.codec.ContainedAssetCodec;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.OverlapBehavior;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.RemovalBehavior;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.CommonLog;
import com.ziggfreed.common.asset.AssetStoreWriter;

/**
 * A family of runtime-generated {@code EntityEffect} assets, one per BUCKET (a channel at a whole-number
 * value), shared by every entity sitting on that bucket: the way to carry a per-entity number through a
 * native mechanism that only reads assets, such as an effect's {@code OutgoingDamage} or
 * {@code DamageResistance}. Mod-agnostic: a consumer names the id prefix, the pack key and the body each
 * bucket gets ({@link EffectBodies} builds the damage-modifier ones), and nothing here knows a skill, a
 * school or a mob.
 *
 * <p><b>Lazy and shared.</b> A bucket's asset is written the first time any entity needs it and reused by
 * everyone after, never one asset per entity (each asset joins the catalogue every client syncs). The
 * write goes through {@link AssetStoreWriter}: inline outside a world tick, queued to the writer thread
 * inside one (a tick holds the asset read lock a store write can never upgrade). {@link #landing} hands
 * back that write's future, and an apply that meets a bucket still landing answers {@link Outcome#PENDING}
 * and runs the caller's {@code onLanded} once it lands, so the caller re-applies then (hop back to the
 * entity's world with {@link #onWorldThread}).
 *
 * <p><b>State lives on the entity.</b> Which bucket an entity carries is read off its own
 * {@code EffectControllerComponent} ({@link #reconcile} sweeps every active effect this grid owns that is
 * not wanted), never a per-entity map here, so nothing can go stale on a disconnect, a world change or an
 * effect some other code removed. A bucket an entity saved with is dropped by the engine on load until
 * this process writes it again, which the next reconcile does.
 *
 * <p>Effect ids are {@code <prefix><channel>_<value>}, a negative value written {@code Minus<n>}. A
 * channel may hold underscores; the value is the last segment.
 */
public final class RuntimeEffectGrid {

    /** One channel at one whole-number value: the unit an effect asset is generated for. */
    public record Bucket(@Nonnull String channel, int value) {
        public Bucket {
            Objects.requireNonNull(channel, "channel");
        }
    }

    /** What an apply did. */
    public enum Outcome {
        /** The effect is on the entity now. */
        APPLIED,
        /** The bucket's asset is still being written; {@code onLanded} runs when it lands. */
        PENDING,
        /** Nothing applied: no controller, a failed write (logged), or an engine refusal. */
        FAILED
    }

    /** One reconcile pass: buckets to apply and owned effect ids to remove. */
    public record Plan(@Nonnull List<Bucket> apply, @Nonnull List<String> remove) {
        public boolean isEmpty() {
            return apply.isEmpty() && remove.isEmpty();
        }
    }

    @Nonnull
    private final String prefix;
    @Nonnull
    private final String packKey;
    @Nonnull
    private final Function<Bucket, String> body;
    @Nonnull
    private final Set<Bucket> registered = ConcurrentHashMap.newKeySet();
    @Nonnull
    private final Map<Bucket, CompletableFuture<Void>> pending = new ConcurrentHashMap<>();
    @Nonnull
    private final Set<Bucket> refused = ConcurrentHashMap.newKeySet();

    /**
     * @param prefix  the effect-id prefix every bucket of this family carries; distinct per family,
     *                since {@link #reconcile} sweeps every active effect under it
     * @param packKey the asset pack the generated effects are loaded under
     * @param body    the {@code EntityEffect} JSON for a bucket (a pure function of the bucket and of
     *                whatever roster it keys; {@link #resynthesizeAll} re-issues every body when that
     *                roster changes)
     */
    public RuntimeEffectGrid(@Nonnull String prefix, @Nonnull String packKey,
            @Nonnull Function<Bucket, String> body) {
        this.prefix = Objects.requireNonNull(prefix, "prefix");
        this.packKey = Objects.requireNonNull(packKey, "packKey");
        this.body = Objects.requireNonNull(body, "body");
    }

    @Nonnull
    public String prefix() {
        return prefix;
    }

    // ==================== Ids and bucket math (pure) ====================

    /** The generated effect id for {@code bucket}. */
    @Nonnull
    public String effectId(@Nonnull Bucket bucket) {
        return prefix + bucket.channel() + "_" + valueToken(bucket.value());
    }

    /** Whether {@code effectId} is one of this family's (case ignored, as the asset map keys ids). */
    public boolean owns(@Nullable String effectId) {
        return effectId != null && effectId.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    /** The channel an owned id names, or {@code null} for an id this family does not own. */
    @Nullable
    public String channelOf(@Nullable String effectId) {
        if (!owns(effectId)) {
            return null;
        }
        int cut = effectId.lastIndexOf('_');
        return cut > prefix.length() ? effectId.substring(prefix.length(), cut) : null;
    }

    /**
     * {@code raw} rounded to the nearest whole number and held to {@code [min, max]}; a non-finite
     * reading is 0. The bucket a consumer's channel reading lands on.
     */
    public static int bucketFor(double raw, int min, int max) {
        if (!Double.isFinite(raw)) {
            return 0;
        }
        long rounded = Math.round(raw);
        return (int) Math.max(min, Math.min(max, rounded));
    }

    /**
     * Decide one reconcile pass from an entity's active effect ids and the buckets it should carry: a
     * wanted bucket already active is left alone (an unchanged pass plans nothing), every owned id that
     * is not wanted is removed (a changed value, a channel gone to zero, a bucket an older run left).
     * Ids this family does not own are ignored, so the entity's whole roster may be handed over.
     */
    @Nonnull
    public Plan plan(@Nonnull Collection<String> activeEffectIds, @Nonnull Collection<Bucket> desired) {
        Map<String, Bucket> wanted = new LinkedHashMap<>();
        for (Bucket bucket : desired) {
            wanted.put(effectId(bucket).toLowerCase(Locale.ROOT), bucket);
        }
        List<Bucket> apply = new ArrayList<>();
        Set<String> active = new HashSet<>();
        for (String id : activeEffectIds) {
            if (id != null) {
                active.add(id.toLowerCase(Locale.ROOT));
            }
        }
        for (Map.Entry<String, Bucket> e : wanted.entrySet()) {
            if (!active.contains(e.getKey())) {
                apply.add(e.getValue());
            }
        }
        List<String> remove = new ArrayList<>();
        for (String id : activeEffectIds) {
            if (owns(id) && !wanted.containsKey(id.toLowerCase(Locale.ROOT))) {
                remove.add(id);
            }
        }
        return new Plan(apply, remove);
    }

    // ==================== Asset landing ====================

    /**
     * The bucket's asset write: complete once the asset is in the store, pending while it is queued off
     * the world thread, {@code null} when the body could not be built (logged). A bucket written this
     * process is a set lookup here; a bucket still landing is never queued twice, later callers ride
     * the same future.
     */
    @Nullable
    public CompletableFuture<Void> landing(@Nonnull Bucket bucket) {
        if (registered.contains(bucket)) {
            return CompletableFuture.completedFuture(null);
        }
        if (refused.contains(bucket)) {
            return null; // the engine refused this body; logged once, retried by resynthesizeAll
        }
        CompletableFuture<Void> queued = pending.get(bucket);
        if (queued != null) {
            return queued;
        }
        String id = effectId(bucket);
        try {
            CompletableFuture<Void> landing = write(Map.of(id, body.apply(bucket)), "runtime effect " + id);
            if (landing.isDone()) {
                settle(bucket, id, !landing.isCompletedExceptionally());
                return registered.contains(bucket) ? landing : null;
            }
            CompletableFuture<Void> raced = pending.putIfAbsent(bucket, landing);
            if (raced != null) {
                return raced; // another caller queued the same body first; ride its write
            }
            landing.whenComplete((landed, failure) -> {
                settle(bucket, id, failure == null);
                pending.remove(bucket, landing);
            });
            return landing;
        } catch (Throwable t) {
            warn("RuntimeEffectGrid: could not generate '" + id + "': " + t.getMessage());
            return null;
        }
    }

    /**
     * Record a finished write: registered only when the asset is really in the store. A write can
     * complete without throwing while the engine refuses the body (a key naming a damage cause that is
     * not loaded), so the asset map is asked rather than the future; a refused bucket is not written
     * again until {@link #resynthesizeAll}.
     */
    private void settle(@Nonnull Bucket bucket, @Nonnull String id, boolean wrote) {
        if (wrote && EntityEffect.getAssetMap().getIndex(id) != Integer.MIN_VALUE) {
            registered.add(bucket);
        } else if (refused.add(bucket)) {
            warn("RuntimeEffectGrid: '" + id + "' did not load; its body was refused (see the asset log)");
        }
    }

    /**
     * Re-write every bucket generated so far, in one batch: a body keys a roster (the damage causes under
     * a school, say) that can change on a reload, and an applied effect reads its asset live, so the new
     * body reaches entities already carrying it with no re-apply. A no-op before the first bucket. A
     * bucket the engine refused before is written afresh the next time anyone needs it.
     */
    public void resynthesizeAll() {
        refused.clear();
        try {
            Map<String, String> bodies = new LinkedHashMap<>();
            for (Bucket bucket : registered) {
                bodies.put(effectId(bucket), body.apply(bucket));
            }
            if (!bodies.isEmpty()) {
                write(bodies, bodies.size() + " runtime effect(s) under " + prefix);
            }
        } catch (Throwable t) {
            warn("RuntimeEffectGrid: re-writing " + prefix + "* failed: " + t.getMessage());
        }
    }

    @Nonnull
    private CompletableFuture<Void> write(@Nonnull Map<String, String> bodies, @Nonnull String what) {
        List<RawAsset<String>> batch = new ArrayList<>(bodies.size());
        for (Map.Entry<String, String> e : bodies.entrySet()) {
            batch.add(new RawAsset<>(null, e.getKey(), null, 0, e.getValue().toCharArray(), null,
                    ContainedAssetCodec.Mode.NONE));
        }
        return AssetStoreWriter.writeBuffers(what, EntityEffect.getAssetStore(), packKey, batch).completion();
    }

    // ==================== Per-entity apply / reconcile (world thread) ====================

    /**
     * Bring the entity's effects of this family into line with {@code desired}, each applied with no
     * expiry: owned effects not wanted come off first (so a channel never carries two buckets, even for
     * part of a tick), then each wanted bucket not on yet goes on. Idempotent. A wanted bucket still
     * landing runs {@code onLanded} when it lands, once per pass. World thread only; never throws.
     */
    public void reconcile(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref,
            @Nonnull Collection<Bucket> desired, @Nullable Runnable onLanded) {
        try {
            EffectControllerComponent ctrl = controller(accessor, ref);
            if (ctrl == null) {
                return;
            }
            Map<String, Integer> active = activeOwned(ctrl);
            Plan plan = plan(active.keySet(), desired);
            for (String id : plan.remove()) {
                Integer idx = active.get(id);
                if (idx != null) {
                    ctrl.removeEffect(ref, idx.intValue(), RemovalBehavior.COMPLETE, accessor);
                }
            }
            boolean retryQueued = false;
            for (Bucket bucket : plan.apply()) {
                if (applyInfinite(accessor, ref, ctrl, bucket, retryQueued ? null : onLanded) == Outcome.PENDING) {
                    retryQueued = true;
                }
            }
        } catch (Throwable t) {
            warn("RuntimeEffectGrid: reconcile under " + prefix + " failed: " + t.getMessage());
        }
    }

    /** Take every effect this family owns off the entity. World thread only; never throws. */
    public void removeAll(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref) {
        reconcile(accessor, ref, List.of(), null);
    }

    /** Apply {@code bucket} with no expiry (see {@link Outcome}). World thread only; never throws. */
    @Nonnull
    public Outcome applyInfinite(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref,
            @Nonnull Bucket bucket, @Nullable Runnable onLanded) {
        try {
            EffectControllerComponent ctrl = controller(accessor, ref);
            return ctrl == null ? Outcome.FAILED : applyInfinite(accessor, ref, ctrl, bucket, onLanded);
        } catch (Throwable t) {
            warn("RuntimeEffectGrid: apply '" + effectId(bucket) + "' failed: " + t.getMessage());
            return Outcome.FAILED;
        }
    }

    /**
     * Apply {@code bucket} for {@code seconds}, overwriting the same bucket's remaining time, after
     * taking off every OTHER bucket of the same channel this entity carries: one live value per channel,
     * so a re-cast at a new value replaces the old one rather than stacking with it. A bucket still
     * landing applies nothing now and runs {@code onLanded} when it lands. World thread only; never
     * throws.
     */
    @Nonnull
    public Outcome applyTimedExclusive(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> ref, @Nonnull Bucket bucket, float seconds, @Nullable Runnable onLanded) {
        try {
            EffectControllerComponent ctrl = controller(accessor, ref);
            if (ctrl == null) {
                return Outcome.FAILED;
            }
            CompletableFuture<Void> landing = landing(bucket);
            if (landing == null) {
                return Outcome.FAILED;
            }
            if (!landing.isDone()) {
                if (onLanded != null) {
                    landing.thenRun(onLanded);
                }
                return Outcome.PENDING;
            }
            String wantedId = effectId(bucket);
            // Resolve the new value first, so a bucket that failed to load never strips the running one.
            EntityEffect effect = EntityEffect.getAssetMap().getAsset(wantedId);
            if (effect == null) {
                return Outcome.FAILED;
            }
            for (Map.Entry<String, Integer> e : activeOwned(ctrl).entrySet()) {
                if (!e.getKey().equalsIgnoreCase(wantedId) && bucket.channel().equals(channelOf(e.getKey()))) {
                    ctrl.removeEffect(ref, e.getValue().intValue(), RemovalBehavior.COMPLETE, accessor);
                }
            }
            return ctrl.addEffect(ref, effect, Math.max(0f, seconds), OverlapBehavior.OVERWRITE, accessor)
                    ? Outcome.APPLIED : Outcome.FAILED;
        } catch (Throwable t) {
            warn("RuntimeEffectGrid: timed apply '" + effectId(bucket) + "' failed: " + t.getMessage());
            return Outcome.FAILED;
        }
    }

    /**
     * A {@code onLanded} that runs {@code task} on {@code world}'s thread: a landing completes on the
     * writer thread, and an entity is only touched from its own world. A world that stopped taking
     * tasks (shutting down) drops it; nobody is left to apply it to.
     */
    @Nonnull
    public static Runnable onWorldThread(@Nonnull World world, @Nonnull Runnable task) {
        return () -> {
            try {
                world.execute(task);
            } catch (Throwable t) {
                fine("RuntimeEffectGrid: re-apply dropped: " + t.getMessage());
            }
        };
    }

    @Nonnull
    private Outcome applyInfinite(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref,
            @Nonnull EffectControllerComponent ctrl, @Nonnull Bucket bucket, @Nullable Runnable onLanded) {
        CompletableFuture<Void> landing = landing(bucket);
        if (landing == null) {
            return Outcome.FAILED;
        }
        if (!landing.isDone()) {
            if (onLanded != null) {
                landing.thenRun(onLanded);
            }
            return Outcome.PENDING;
        }
        String id = effectId(bucket);
        int idx = EntityEffect.getAssetMap().getIndex(id);
        if (idx == Integer.MIN_VALUE) {
            return Outcome.FAILED; // the write failed and was logged
        }
        EntityEffect effect = EntityEffect.getAssetMap().getAsset(idx);
        if (effect == null) {
            return Outcome.FAILED;
        }
        return ctrl.addInfiniteEffect(ref, idx, effect, accessor) ? Outcome.APPLIED : Outcome.FAILED;
    }

    @Nullable
    private static EffectControllerComponent controller(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nullable Ref<EntityStore> ref) {
        if (ref == null || !ref.isValid()) {
            return null;
        }
        return accessor.getComponent(ref, EffectControllerComponent.getComponentType());
    }

    /** The entity's active effects this family owns, id to asset index (a snapshot: removal mutates). */
    @Nonnull
    private Map<String, Integer> activeOwned(@Nonnull EffectControllerComponent ctrl) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Integer idx : new ArrayList<>(ctrl.getActiveEffects().keySet())) {
            if (idx == null) {
                continue;
            }
            EntityEffect effect = EntityEffect.getAssetMap().getAsset(idx.intValue());
            String id = effect != null ? effect.getId() : null;
            if (owns(id)) {
                out.put(id, idx);
            }
        }
        return out;
    }

    @Nonnull
    static String valueToken(int value) {
        return value < 0 ? "Minus" + (-(long) value) : Integer.toString(value);
    }

    private static void warn(@Nonnull String message) {
        try {
            CommonLog.LOGGER.atWarning().log("[ziggfreed-common][effect-grid] " + message);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM: the flogger LOGGER can throw; swallow it.
        }
    }

    private static void fine(@Nonnull String message) {
        try {
            CommonLog.LOGGER.atFine().log("[ziggfreed-common][effect-grid] " + message);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM: the flogger LOGGER can throw; swallow it.
        }
    }
}