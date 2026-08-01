package com.ziggfreed.common.interaction.param;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The immutable per-FIRE payload a chain initiator attaches to one chain execution so the
 * consumer's own custom interaction Types inside that chain can resolve per-caster numbers:
 * which scope (an ability id, a station id, whatever the consumer keys on) is firing, who fired
 * it, an opaque consumer payload, and an optional string var overlay.
 *
 * <p><b>FIELD-ADDITIVE by construction</b>, explicitly modelled on
 * {@code com.ziggfreed.common.cast.HitContext}: a new field is a new builder method plus a new
 * getter and never breaks an existing {@link ParamFoldResolver} implementer - unlike widening a
 * JDK functional interface's generic parameter, which breaks every implementer at once. Do not
 * widen or reorder anything already declared here; only append.
 *
 * <p>Common carries ZERO consumer vocabulary: {@link #scopeId()} is an opaque string and
 * {@link #payload()} an opaque object. This class never names an ability, a mastery, a modifier
 * shape, or any other domain concept - the consumer supplies and interprets both.
 */
public final class CastScope {

    @Nullable private final String scopeId;
    @Nullable private final UUID casterId;
    @Nullable private final Object payload;
    @Nonnull private final Map<String, String> vars;
    private final long firedAtMillis;

    private CastScope(@Nonnull Builder b) {
        this.scopeId = b.scopeId;
        this.casterId = b.casterId;
        this.payload = b.payload;
        this.vars = Collections.unmodifiableMap(new LinkedHashMap<>(b.vars));
        this.firedAtMillis = b.firedAtMillisSet ? b.firedAtMillis : System.currentTimeMillis();
    }

    /** The firing scope id - an ability id, a station id, whatever the consumer keys on. */
    @Nullable
    public String scopeId() {
        return scopeId;
    }

    /** The caster's stable entity uuid, when the initiator knows it. */
    @Nullable
    public UUID casterId() {
        return casterId;
    }

    /** An opaque consumer payload (a folded modifier set, a spec object, ...). Never interpreted here. */
    @Nullable
    public Object payload() {
        return payload;
    }

    /** Immutable, never null, possibly empty. Overlaid onto the chain's {@code InteractionVars}. */
    @Nonnull
    public Map<String, String> vars() {
        return vars;
    }

    /** {@code System.currentTimeMillis()} at build time unless set explicitly on the builder. */
    public long firedAtMillis() {
        return firedAtMillis;
    }

    @Override
    @Nonnull
    public String toString() {
        return "CastScope{" +
                "scopeId='" + scopeId + '\'' +
                ", casterId=" + casterId +
                ", payload=" + payload +
                ", vars=" + vars +
                ", firedAtMillis=" + firedAtMillis +
                '}';
    }

    @Nonnull
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for a {@link CastScope}. New optional fields are added here without
     * breaking a {@link ParamFoldResolver} implementer.
     */
    public static final class Builder {
        @Nullable private String scopeId;
        @Nullable private UUID casterId;
        @Nullable private Object payload;
        @Nonnull private final Map<String, String> vars = new LinkedHashMap<>();
        private long firedAtMillis;
        private boolean firedAtMillisSet;

        private Builder() {
        }

        @Nonnull
        public Builder scopeId(@Nullable String scopeId) {
            this.scopeId = scopeId;
            return this;
        }

        @Nonnull
        public Builder casterId(@Nullable UUID casterId) {
            this.casterId = casterId;
            return this;
        }

        @Nonnull
        public Builder payload(@Nullable Object payload) {
            this.payload = payload;
            return this;
        }

        /**
         * Sets one var. A null or blank key is ignored (no-op); a null value REMOVES any
         * existing entry for {@code key} instead of adding one.
         */
        @Nonnull
        public Builder var(@Nullable String key, @Nullable String value) {
            if (key == null || key.isBlank()) {
                return this;
            }
            if (value == null) {
                vars.remove(key);
            } else {
                vars.put(key, value);
            }
            return this;
        }

        /**
         * Merges (does not replace) {@code vars} into the accumulating map, one entry at a time
         * through the same rules as {@link #var(String, String)}. A null map is ignored.
         */
        @Nonnull
        public Builder vars(@Nullable Map<String, String> vars) {
            if (vars == null) {
                return this;
            }
            for (Map.Entry<String, String> entry : vars.entrySet()) {
                var(entry.getKey(), entry.getValue());
            }
            return this;
        }

        @Nonnull
        public Builder firedAtMillis(long firedAtMillis) {
            this.firedAtMillis = firedAtMillis;
            this.firedAtMillisSet = true;
            return this;
        }

        @Nonnull
        public CastScope build() {
            return new CastScope(this);
        }
    }
}
