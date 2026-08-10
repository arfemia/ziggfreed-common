package com.ziggfreed.common.interaction.param;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.meta.MetaKey;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.ziggfreed.common.util.SafeLog;

/**
 * Stashes and reads a {@link CastScope} on a live interaction chain - the per-fire context door.
 *
 * <h2>Two engine doors; why the meta store is primary</h2>
 * <p><b>Primary: the context meta store.</b> {@link InteractionContext} owns a
 * {@code DynamicMetaStore<InteractionContext>} over the OPEN
 * {@code Interaction.CONTEXT_META_REGISTRY} ({@code InteractionContext.java:108,134};
 * {@code Interaction.java:208}). {@code MetaRegistry.registerMetaObject()} is public and its
 * no-arg overload registers a transient, null-defaulted {@code MetaKey<T>}
 * ({@code IMetaRegistry.java:56}). {@code DynamicMetaStore.putMetaObject}/
 * {@code getIfPresentMetaObject}/{@code removeMetaObject} are all public
 * ({@code DynamicMetaStore.java:38,43}). <b>Critically, {@code InteractionContext.duplicate()}
 * does {@code ctx.metaStore.copyFrom(metaStore)} ({@code InteractionContext.java:294-297}), and a
 * Selector hit fork IS exactly a {@code duplicate()} that only overwrites its
 * {@code TARGET_ENTITY}/{@code HIT_LOCATION} meta afterwards
 * ({@code SelectInteraction.java:331-336}) - therefore a scope stashed once on the root context
 * SURVIVES into every per-target fork automatically,</b> with zero forwarding code on the
 * consumer's part. That survival property is the whole reason this door was picked over the
 * alternative below: it is typed, transient (never persisted), needs no string coercion, and
 * rides the engine's own fork-copy mechanism for free.
 *
 * <p><b>Secondary: {@link #applyVars}, the {@code InteractionVars} getter.</b>
 * {@code setInteractionVarsGetter(Function<InteractionContext, Map<String,String>>)}
 * ({@code InteractionContext.java:434}) is ALSO copied by {@code duplicate()} (line 296) and
 * feeds {@code Replace}/{@code InteractionVars} resolution in native chain content. It is
 * String -&gt; String only, so it exists for ASSET SELECTION (picking an impact fragment by
 * school, a variant id, ...) - never for numbers; {@link ParamSlot}/{@link ParamFold} are the
 * number path, riding the primary door.
 *
 * <h2>install() at setup(), never an eager static</h2>
 * <p><b>{@link #install()} MUST be called from the consumer plugin's {@code setup()}, and the
 * {@code MetaKey} is NEVER an eager static field.</b> Registering the key touches
 * {@code Interaction.CONTEXT_META_REGISTRY}, a public static field on {@code Interaction}, which
 * forces {@code Interaction}'s {@code <clinit>} - the same RangeValidator/HytaleLogger trap
 * documented at {@code RunCommandInteraction.java:127-137}: it throws
 * {@code IllegalStateException("Log manager wasn't set!")} outside a live server. A private
 * {@code static volatile MetaKey<CastScope>} field, assigned inside {@link #install()} under a
 * lock, defers that first touch to plugin setup time instead of class-load time. Before
 * {@link #install()} runs, every method here is a guarded no-op ({@code false} / {@code null}),
 * so a unit JVM - and a consumer that never installs at all - both behave sanely rather than
 * crashing.
 *
 * <p>{@link #install()} is idempotent and thread-safe; a second (or concurrent) call is a no-op
 * returning {@code true} once installed. Registering the SAME key twice would leak a second,
 * unused registry slot per extra call - the registry has no unregister - so idempotence is not
 * just convenience, it prevents a slow leak across repeated setup() calls (tests, hot reload).
 *
 * <p>World-thread (mutates a live context's meta store / var getter).
 */
public final class CastScopes {

    private static final Object LOCK = new Object();

    @Nullable
    private static volatile MetaKey<CastScope> KEY;

    private CastScopes() {
    }

    /**
     * Register the {@link CastScope} meta key. Call once from the consumer plugin's
     * {@code setup()}, before any chain that needs to stash a scope fires.
     *
     * @return {@code true} when the key is registered and ready - already-installed counts as
     *         success - {@code false} only when the underlying engine registration itself threw
     *         (guarded WARN already logged).
     */
    public static boolean install() {
        if (KEY != null) {
            return true;
        }
        synchronized (LOCK) {
            if (KEY != null) {
                return true;
            }
            try {
                KEY = Interaction.CONTEXT_META_REGISTRY.registerMetaObject();
                SafeLog.info("[interaction.param] CastScope meta key installed");
                return true;
            } catch (Throwable t) {
                SafeLog.warn("[interaction.param] CastScope meta key installation failed", t);
                return false;
            }
        }
    }

    /** Whether {@link #install()} has successfully registered the meta key. */
    public static boolean isInstalled() {
        return KEY != null;
    }

    /**
     * Stash {@code scope} on {@code ctx}'s meta store.
     *
     * @return {@code true} when the scope was written; {@code false} on a null argument, before
     *         {@link #install()}, or on any engine throw (guarded WARN).
     */
    public static boolean stash(@Nullable InteractionContext ctx, @Nullable CastScope scope) {
        MetaKey<CastScope> key = KEY;
        if (ctx == null || scope == null || key == null) {
            return false;
        }
        try {
            ctx.getMetaStore().putMetaObject(key, scope);
            return true;
        } catch (Throwable t) {
            SafeLog.warn("[interaction.param] CastScope stash failed", t);
            return false;
        }
    }

    /**
     * Read back the scope stashed on {@code ctx} - either stashed directly, or copied into it by
     * an engine {@code duplicate()} fork (a Selector hit fork included).
     *
     * @return the scope, or {@code null} on a null argument, before {@link #install()}, no scope
     *         stashed, or any engine throw (guarded FINE).
     */
    @Nullable
    public static CastScope read(@Nullable InteractionContext ctx) {
        MetaKey<CastScope> key = KEY;
        if (ctx == null || key == null) {
            return null;
        }
        try {
            return ctx.getMetaStore().getIfPresentMetaObject(key);
        } catch (Throwable t) {
            SafeLog.fine("[interaction.param] CastScope read failed: " + t.getMessage());
            return null;
        }
    }

    /**
     * Remove the stashed scope from {@code ctx}.
     *
     * @return {@code true} when an entry was actually removed; {@code false} on a null argument,
     *         before {@link #install()}, nothing stashed, or any engine throw (guarded WARN).
     */
    public static boolean clear(@Nullable InteractionContext ctx) {
        MetaKey<CastScope> key = KEY;
        if (ctx == null || key == null) {
            return false;
        }
        try {
            return ctx.getMetaStore().removeMetaObject(key) != null;
        } catch (Throwable t) {
            SafeLog.warn("[interaction.param] CastScope clear failed", t);
            return false;
        }
    }

    /**
     * A {@code Consumer<InteractionContext>} that stashes {@code scope} - the argument form
     * {@code NativeChainFire.fire(..., Consumer)} takes to decorate a freshly-built context
     * before the chain is queued. A null {@code scope} yields a no-op consumer (never null
     * itself), so a caller can pass this straight through without a null check of its own.
     */
    @Nonnull
    public static Consumer<InteractionContext> decorator(@Nullable CastScope scope) {
        if (scope == null) {
            return ctx -> {
            };
        }
        return ctx -> stash(ctx, scope);
    }

    /**
     * Overlay {@code scope.vars()} onto {@code ctx}'s {@code InteractionVars} resolution (the
     * SECONDARY door, for {@code Replace} slot picks - string values only). Wraps the context's
     * CURRENT getter so native item vars survive underneath; scope vars win on a key collision.
     *
     * <p>Caveat from the composability mine, restated here because it bites silently otherwise:
     * {@code InteractionVars} resolve from ONE FLAT MAP spanning the whole execute/fork tree -
     * there is no per-node scoping, so an unprefixed consumer var can collide with a native
     * item's own var or another consumer's var anywhere else in the same chain. Prefix every
     * consumer var (e.g. {@code "mmo.school"} not {@code "school"}).
     *
     * @return {@code true} on success (including the empty-vars no-op case); {@code false} on a
     *         null argument, before {@link #install()}, or any engine throw (guarded WARN). Empty
     *         {@code scope.vars()} still requires {@link #install()} to have run, for uniformity
     *         with {@link #stash}/{@link #read}/{@link #clear} - all four doors gate on the same
     *         install flag even though this one does not itself touch the meta store.
     */
    public static boolean applyVars(@Nullable InteractionContext ctx, @Nullable CastScope scope) {
        if (ctx == null || scope == null || KEY == null) {
            return false;
        }
        Map<String, String> overlay = scope.vars();
        if (overlay.isEmpty()) {
            return true;
        }
        try {
            Function<InteractionContext, Map<String, String>> previous = ctx.getInteractionVarsGetter();
            ctx.setInteractionVarsGetter(current -> {
                Map<String, String> merged = new LinkedHashMap<>();
                if (previous != null) {
                    Map<String, String> base = previous.apply(current);
                    if (base != null) {
                        merged.putAll(base);
                    }
                }
                merged.putAll(overlay);
                return merged;
            });
            return true;
        } catch (Throwable t) {
            SafeLog.warn("[interaction.param] CastScope applyVars failed", t);
            return false;
        }
    }
}
