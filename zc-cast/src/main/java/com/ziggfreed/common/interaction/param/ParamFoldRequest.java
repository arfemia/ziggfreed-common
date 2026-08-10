package com.ziggfreed.common.interaction.param;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * One fire-time parameter-fold question, as an immutable FIELD-ADDITIVE carrier - explicitly
 * modelled on {@code com.ziggfreed.common.cast.HitContext}: a new field is a new builder method
 * plus a new getter and never breaks a {@link ParamFoldResolver} implementer, unlike widening a
 * JDK functional interface's generic parameter. Do not widen or reorder anything already
 * declared here; only append.
 */
public final class ParamFoldRequest {

    @Nullable private final Store<EntityStore> store;
    @Nullable private final Ref<EntityStore> caster;
    @Nullable private final String scopeId;
    @Nonnull private final String paramKey;
    private final double base;
    @Nullable private final Object payload;
    @Nullable private final CastScope scope;

    private ParamFoldRequest(@Nonnull Builder b) {
        this.store = b.store;
        this.caster = b.caster;
        this.scopeId = b.scopeId;
        this.paramKey = b.paramKey;
        this.base = b.base;
        this.payload = b.payload;
        this.scope = b.scope;
    }

    @Nullable
    public Store<EntityStore> store() {
        return store;
    }

    /** The firing entity whose per-caster state decides the value. */
    @Nullable
    public Ref<EntityStore> caster() {
        return caster;
    }

    /** Convenience projection of {@link #scope()}'s scopeId; null when no scope is attached. */
    @Nullable
    public String scopeId() {
        return scopeId;
    }

    /** The declared parameter key. Never null; may be blank, which means "unfoldable". */
    @Nonnull
    public String paramKey() {
        return paramKey;
    }

    /** The authored base value the resolver folds onto. */
    public double base() {
        return base;
    }

    /** Convenience projection of {@link #scope()}'s payload. */
    @Nullable
    public Object payload() {
        return payload;
    }

    @Nullable
    public CastScope scope() {
        return scope;
    }

    @Override
    @Nonnull
    public String toString() {
        return "ParamFoldRequest{" +
                "caster=" + caster +
                ", scopeId='" + scopeId + '\'' +
                ", paramKey='" + paramKey + '\'' +
                ", base=" + base +
                ", payload=" + payload +
                '}';
    }

    @Nonnull
    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder for a {@link ParamFoldRequest}. */
    public static final class Builder {
        @Nullable private Store<EntityStore> store;
        @Nullable private Ref<EntityStore> caster;
        @Nullable private CastScope scope;
        @Nullable private String scopeId;
        private boolean scopeIdExplicit;
        @Nullable private Object payload;
        @Nonnull private String paramKey = "";
        private double base;

        private Builder() {
        }

        @Nonnull
        public Builder store(@Nullable Store<EntityStore> store) {
            this.store = store;
            return this;
        }

        @Nonnull
        public Builder caster(@Nullable Ref<EntityStore> caster) {
            this.caster = caster;
            return this;
        }

        /**
         * Sets the scope AND back-fills the {@link #scopeId} / payload projections. The payload
         * projection always follows the most recent {@code scope}; the {@code scopeId}
         * projection only follows it when {@link #scopeId(String)} has not been explicitly
         * called (an explicit override always wins, regardless of call order).
         */
        @Nonnull
        public Builder scope(@Nullable CastScope scope) {
            this.scope = scope;
            this.payload = scope != null ? scope.payload() : null;
            if (!scopeIdExplicit) {
                this.scopeId = scope != null ? scope.scopeId() : null;
            }
            return this;
        }

        /** Explicit override; wins over the {@link #scope(CastScope)} projection regardless of call order. */
        @Nonnull
        public Builder scopeId(@Nullable String scopeId) {
            this.scopeId = scopeId;
            this.scopeIdExplicit = true;
            return this;
        }

        /** Null or blank normalizes to {@code ""}. */
        @Nonnull
        public Builder paramKey(@Nullable String paramKey) {
            this.paramKey = (paramKey == null || paramKey.isBlank()) ? "" : paramKey;
            return this;
        }

        @Nonnull
        public Builder base(double base) {
            this.base = base;
            return this;
        }

        @Nonnull
        public ParamFoldRequest build() {
            return new ParamFoldRequest(this);
        }
    }
}
