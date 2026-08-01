package com.ziggfreed.common.interaction.param;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * A PER-CONSUMER instance holding that consumer's {@link ParamFoldResolver} - the same
 * per-consumer-instance idiom as {@code com.ziggfreed.common.cast.OnHitRegistry}: a consumer news
 * up its own {@code ParamFold} (or several, one per parameter family) so two mods sharing this
 * jar never see each other's fold.
 *
 * <p>Ships with NO resolver: an un-configured fold is the IDENTITY, returning the authored
 * base. Every degradation returns {@link ParamFoldRequest#base()} (or {@code 0.0} when there is
 * no request to read a base from at all):
 * <ul>
 *   <li>a null request - returns {@code 0.0} (there is no base to fall back on);</li>
 *   <li>no resolver configured - returns the base unchanged;</li>
 *   <li>a null or blank {@link ParamFoldRequest#paramKey()} - returns the base WITHOUT calling
 *       the resolver at all;</li>
 *   <li>a resolver that throws - returns the base, with one guarded WARN carrying this fold's
 *       label;</li>
 *   <li>a resolver that returns {@code NaN} or an infinity - returns the base, with one guarded
 *       FINE (checked via {@link Double#isFinite(double)}).</li>
 * </ul>
 *
 * <p>{@link #setResolver} is last-write-wins; passing {@code null} clears back to identity.
 */
public final class ParamFold {

    @Nonnull private final String label;
    @Nullable private volatile ParamFoldResolver resolver;

    /** A fold with no label ({@code "unlabeled"} in log lines). */
    public ParamFold() {
        this("unlabeled");
    }

    /** A fold identified by {@code label} in its guarded log lines. */
    public ParamFold(@Nullable String label) {
        this.label = (label == null || label.isBlank()) ? "unlabeled" : label;
    }

    /** Sets the fold's resolver. {@code null} clears back to identity (return base unchanged). */
    public void setResolver(@Nullable ParamFoldResolver resolver) {
        this.resolver = resolver;
    }

    @Nullable
    public ParamFoldResolver getResolver() {
        return resolver;
    }

    public boolean hasResolver() {
        return resolver != null;
    }

    /**
     * Resolve {@code request} through this fold's resolver, applying every degradation rule
     * documented on the class.
     */
    public double resolve(@Nullable ParamFoldRequest request) {
        if (request == null) {
            return 0.0;
        }
        double base = request.base();
        ParamFoldResolver r = resolver;
        if (r == null) {
            return base;
        }
        String key = request.paramKey();
        if (key.isBlank()) {
            return base;
        }
        double result;
        try {
            result = r.fold(request);
        } catch (Throwable t) {
            SafeLog.warn("[interaction.param] ParamFold '" + label + "' resolver threw for key '" + key + "'", t);
            return base;
        }
        if (!Double.isFinite(result)) {
            SafeLog.fine("[interaction.param] ParamFold '" + label + "' resolver returned non-finite ("
                    + result + ") for key '" + key + "'");
            return base;
        }
        return result;
    }

    /** Convenience: builds the request internally and resolves it. */
    public double resolve(@Nullable Store<EntityStore> store,
                          @Nullable Ref<EntityStore> caster,
                          @Nullable CastScope scope,
                          @Nullable String paramKey,
                          double base) {
        ParamFoldRequest request = ParamFoldRequest.builder()
                .store(store)
                .caster(caster)
                .scope(scope)
                .paramKey(paramKey)
                .base(base)
                .build();
        return resolve(request);
    }
}
