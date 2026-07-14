package com.ziggfreed.common.cast;

import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.UnaryOperator;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.ZiggfreedCommonPlugin;

/**
 * ONE shared per-target hit-application routine: dispatch the damage, then an optional on-hit hook,
 * then an optional per-hit sound and per-hit particle, reproducing the exact sequence + catch policy
 * a consumer's independently-copied {@code damage + onHit + sound} hit loops shared. Lifted config-
 * free from a consumer; the two consumer-coupled knobs it had - an XP-attribution wrap around the
 * damage dispatch, and a per-hit sound call - are replaced by opaque seams a consumer supplies, so
 * nothing consumer-specific enters this jar.
 *
 * <p>Per-caller policy is pinned once per hit loop by a {@link HitRequest} (its fields are loop-
 * invariant) and reused for every resolved target. The knobs, each a copy differed on:
 * <ol>
 *   <li><b>Damage sink</b> - a live {@code Store} (synchronous callers) vs a {@code CommandBuffer}
 *       (tick-driven callers) is captured by the caller's own {@link DamageSink} closure; the sink
 *       builds the {@code Damage} from the request's source / cause / amount so this class never
 *       constructs an engine {@code Damage} itself.</li>
 *   <li><b>Damage source / cause / amount</b> - each a request knob, passed to the sink per hit.</li>
 *   <li><b>Origin decorator</b> - an opaque {@link UnaryOperator}{@code <Runnable>} wrapping EXACTLY
 *       the damage-dispatch call (default identity). A consumer that routes XP attribution through a
 *       thread-local around its damage dispatch supplies a decorator that installs it; a consumer
 *       with no attribution needs nothing.</li>
 *   <li><b>onHit hook</b> - a {@link HitAction} (rich {@link HitContext}) OR a bridged
 *       {@code BiConsumer<Store, Ref>} (the historical shape, invoked with no context allocation).
 *       Null skips it.</li>
 *   <li><b>Per-hit sound</b> - an opaque {@code BiConsumer<Store, Vector3d>} sink (default none),
 *       fired AFTER onHit at the hit position, only when the request sets it.</li>
 *   <li><b>Per-hit particle</b> - a particle asset id spawned via the common {@link ModelParticleService}
 *       at the hit position, only when set.</li>
 *   <li><b>Damage-failure log level</b> - WARN or FINE per request (preserved per caller, not
 *       unified).</li>
 *   <li><b>Return value</b> - {@link #applyHit} reports whether the damage dispatch completed without
 *       throwing, so a caller can keep its own post-hit bookkeeping (pierce countdown, dedup) gated
 *       on a successful dispatch exactly as before.</li>
 * </ol>
 *
 * <p>Target RESOLUTION and cross-frame bookkeeping stay in each caller; this class is only the
 * per-already-resolved-target application. All logging routes through the guarded common LOGGER.
 */
public final class HitResolver {

    /**
     * The caller's damage dispatch. The caller captures its live {@code Store} or {@code CommandBuffer}
     * in the closure and builds the engine {@code Damage} from the passed source / cause / amount, so
     * common neither picks the accessor nor constructs a {@code Damage} itself.
     */
    @FunctionalInterface
    public interface DamageSink {
        void execute(@Nonnull Ref<EntityStore> target,
                     @Nonnull Damage.Source source,
                     @Nonnull DamageCause cause,
                     float amount);
    }

    private HitResolver() {
    }

    /**
     * Apply one hit against {@code target}: run the damage dispatch under the origin decorator, then
     * the optional onHit hook, then the optional per-hit sound + particle. Returns {@code true} when
     * the damage dispatch did not throw (the caller's post-hit bookkeeping was gated on this),
     * {@code false} when it threw (logged per the request's WARN/FINE policy).
     *
     * @param store   the live store (the accessor for the on-hit {@link HitContext} and the sound /
     *                particle spawns); a bridged {@code BiConsumer} onHit receives this store directly
     * @param req     the loop-invariant per-caller policy
     * @param target  the resolved hit target
     * @param hitPos  the hit position; read only for the per-hit sound / particle and the on-hit
     *                {@link HitContext} position, ignored otherwise
     */
    public static boolean applyHit(@Nonnull Store<EntityStore> store,
                                   @Nonnull HitRequest req,
                                   @Nonnull Ref<EntityStore> target,
                                   @Nullable Vector3d hitPos) {
        try {
            Runnable dispatch = () -> req.sink.execute(target, req.source, req.cause, req.amount);
            req.originDecorator.apply(dispatch).run();
            fireOnHit(store, req, target, hitPos);
            if (req.perHitSound != null && hitPos != null) {
                req.perHitSound.accept(store, hitPos);
            }
            if (req.perHitParticleAsset != null && hitPos != null) {
                ModelParticleService.spawnAt(store, req.perHitParticleAsset, hitPos);
            }
            return true;
        } catch (Throwable dmgErr) {
            String msg = req.failLabel + " damage failed: " + dmgErr.getMessage();
            if (req.warnOnDamageFailure) {
                warn(msg);
            } else {
                fine(msg);
            }
            return false;
        }
    }

    private static void fireOnHit(@Nonnull Store<EntityStore> store,
                                  @Nonnull HitRequest req,
                                  @Nonnull Ref<EntityStore> target,
                                  @Nullable Vector3d hitPos) {
        // The bridged BiConsumer path fires with NO HitContext allocation (byte-parity with the
        // historical shape). Only the newer HitAction path builds a context.
        if (req.onHitBiConsumer != null) {
            try {
                req.onHitBiConsumer.accept(store, target);
            } catch (Throwable hitErr) {
                fine(req.onHitFailLabel + " failed: " + hitErr.getMessage());
            }
            return;
        }
        if (req.onHitAction != null) {
            try {
                HitContext ctx = HitContext.builder()
                        .accessor(store)
                        .target(target)
                        .source(req.sourceRef)
                        .sourcePlayerId(req.sourcePlayerId)
                        .damageAmount(req.amount)
                        .position(hitPos)
                        .cause(req.hitCauseLabel)
                        .build();
                req.onHitAction.onHit(ctx);
            } catch (Throwable hitErr) {
                fine(req.onHitFailLabel + " failed: " + hitErr.getMessage());
            }
        }
    }

    /**
     * Immutable per-caller policy for a run of {@link #applyHit} calls. Built once per hit loop and
     * reused for every resolved target.
     */
    public static final class HitRequest {
        private final DamageSink sink;
        private final Damage.Source source;
        private final DamageCause cause;
        private final float amount;
        @Nonnull private final UnaryOperator<Runnable> originDecorator;
        @Nullable private final HitAction onHitAction;
        @Nullable private final BiConsumer<Store<EntityStore>, Ref<EntityStore>> onHitBiConsumer;
        private final String failLabel;
        private final boolean warnOnDamageFailure;
        private final String onHitFailLabel;
        @Nullable private final BiConsumer<Store<EntityStore>, Vector3d> perHitSound;
        @Nullable private final String perHitParticleAsset;
        @Nullable private final Ref<EntityStore> sourceRef;
        @Nullable private final UUID sourcePlayerId;
        @Nullable private final String hitCauseLabel;

        private HitRequest(@Nonnull Builder b) {
            this.sink = b.sink;
            this.source = b.source;
            this.cause = b.cause;
            this.amount = b.amount;
            this.originDecorator = b.originDecorator != null ? b.originDecorator : UnaryOperator.identity();
            this.onHitAction = b.onHitAction;
            this.onHitBiConsumer = b.onHitBiConsumer;
            this.failLabel = b.failLabel;
            this.warnOnDamageFailure = b.warnOnDamageFailure;
            this.onHitFailLabel = b.onHitFailLabel != null ? b.onHitFailLabel : b.failLabel + " onHit";
            this.perHitSound = b.perHitSound;
            this.perHitParticleAsset = b.perHitParticleAsset;
            this.sourceRef = b.sourceRef;
            this.sourcePlayerId = b.sourcePlayerId;
            this.hitCauseLabel = b.hitCauseLabel;
        }

        @Nonnull
        public static Builder builder() {
            return new Builder();
        }

        /** Fluent builder for a {@link HitRequest}. */
        public static final class Builder {
            private DamageSink sink;
            private Damage.Source source;
            private DamageCause cause;
            private float amount;
            @Nullable private UnaryOperator<Runnable> originDecorator;
            @Nullable private HitAction onHitAction;
            @Nullable private BiConsumer<Store<EntityStore>, Ref<EntityStore>> onHitBiConsumer;
            private String failLabel = "Cast";
            private boolean warnOnDamageFailure;
            @Nullable private String onHitFailLabel;
            @Nullable private BiConsumer<Store<EntityStore>, Vector3d> perHitSound;
            @Nullable private String perHitParticleAsset;
            @Nullable private Ref<EntityStore> sourceRef;
            @Nullable private UUID sourcePlayerId;
            @Nullable private String hitCauseLabel;

            /** The damage dispatch (captures the caller's store / command buffer, builds the Damage). Required. */
            @Nonnull
            public Builder sink(@Nonnull DamageSink sink) {
                this.sink = sink;
                return this;
            }

            /** The pre-built damage source, passed to the sink per hit. */
            @Nonnull
            public Builder source(@Nonnull Damage.Source source) {
                this.source = source;
                return this;
            }

            @Nonnull
            public Builder cause(@Nonnull DamageCause cause) {
                this.cause = cause;
                return this;
            }

            @Nonnull
            public Builder amount(float amount) {
                this.amount = amount;
                return this;
            }

            /**
             * The decorator wrapping EXACTLY the damage-dispatch runnable. Default identity (no wrap).
             * A consumer routing XP attribution supplies {@code body -> () -> installAttribution(body)}
             * so the attribution rides the dispatch and nothing else.
             */
            @Nonnull
            public Builder originDecorator(@Nullable UnaryOperator<Runnable> originDecorator) {
                this.originDecorator = originDecorator;
                return this;
            }

            /** The rich on-hit hook. Mutually exclusive with {@link #onHit(BiConsumer)} (last set wins). */
            @Nonnull
            public Builder onHit(@Nullable HitAction onHit) {
                this.onHitAction = onHit;
                this.onHitBiConsumer = null;
                return this;
            }

            /**
             * The historical on-hit hook shape - fired with the live store + target and NO context
             * allocation. Mutually exclusive with {@link #onHit(HitAction)} (last set wins).
             */
            @Nonnull
            public Builder onHit(@Nullable BiConsumer<Store<EntityStore>, Ref<EntityStore>> onHit) {
                this.onHitBiConsumer = onHit;
                this.onHitAction = null;
                return this;
            }

            /** Log prefix for the damage-failure and (unless overridden) onHit-failure messages. */
            @Nonnull
            public Builder failLabel(@Nonnull String failLabel) {
                this.failLabel = failLabel;
                return this;
            }

            /** true -&gt; damage failures log WARN; false -&gt; FINE. */
            @Nonnull
            public Builder warnOnDamageFailure(boolean warn) {
                this.warnOnDamageFailure = warn;
                return this;
            }

            /** Explicit onHit-failure prefix when it differs from {@code failLabel + " onHit"}. */
            @Nonnull
            public Builder onHitFailLabel(@Nonnull String onHitFailLabel) {
                this.onHitFailLabel = onHitFailLabel;
                return this;
            }

            /**
             * The per-hit sound sink, fired {@code accept(store, hitPos)} after onHit; null skips it.
             * A consumer captures its sound asset + context in the closure.
             */
            @Nonnull
            public Builder perHitSound(@Nullable BiConsumer<Store<EntityStore>, Vector3d> perHitSound) {
                this.perHitSound = perHitSound;
                return this;
            }

            /** Per-hit particle asset id, spawned at the hit position via {@link ModelParticleService}; null skips it. */
            @Nonnull
            public Builder perHitParticle(@Nullable String particleAsset) {
                this.perHitParticleAsset = particleAsset;
                return this;
            }

            /** Source ref for the on-hit {@link HitContext} (the HitAction path only); default null. */
            @Nonnull
            public Builder sourceRef(@Nullable Ref<EntityStore> sourceRef) {
                this.sourceRef = sourceRef;
                return this;
            }

            /** Source player UUID for the on-hit {@link HitContext} (the HitAction path only); default null. */
            @Nonnull
            public Builder sourcePlayerId(@Nullable UUID sourcePlayerId) {
                this.sourcePlayerId = sourcePlayerId;
                return this;
            }

            /** Opaque cause label for the on-hit {@link HitContext} (the HitAction path only); default null. */
            @Nonnull
            public Builder hitCauseLabel(@Nullable String hitCauseLabel) {
                this.hitCauseLabel = hitCauseLabel;
                return this;
            }

            @Nonnull
            public HitRequest build() {
                return new HitRequest(this);
            }
        }
    }

    private static void warn(@Nonnull String message) {
        try {
            ZiggfreedCommonPlugin.LOGGER.atWarning().log("[ziggfreed-common][hit] " + message);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM: the flogger LOGGER can throw; swallow it.
        }
    }

    private static void fine(@Nonnull String message) {
        try {
            ZiggfreedCommonPlugin.LOGGER.atFine().log("[ziggfreed-common][hit] " + message);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM: the flogger LOGGER can throw; swallow it.
        }
    }
}
