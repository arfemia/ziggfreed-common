package com.ziggfreed.common.factor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The immutable question a {@link FactorProvider} is asked: everything a resolver might need to
 * turn a namespaced factor id into a number, with EVERY leaf independently nullable so a caller
 * supplies only what its own evaluation site actually has.
 *
 * <p><b>Field-additive by design.</b> A new leaf is a new nullable field plus a builder method,
 * so a provider written against an older shape keeps compiling and keeps working - which is what
 * lets one vocabulary serve evaluation sites as different as an NPC placement sweep (no subject,
 * no store) and a dialogue option check (both).
 *
 * <p>The leaves are orthogonal, never a mode:
 * <ul>
 *   <li>{@link #param()} - the authored argument beside the factor id, interpreted only by the
 *       provider that owns the id.</li>
 *   <li>{@link #world()} - the world the question is being asked in.</li>
 *   <li>{@link #store()} / {@link #subject()} - the entity the question is ABOUT (usually the
 *       acting player) and the store to read it through. <b>Live world-thread context, valid
 *       ONLY synchronously during the {@code resolve} call</b>; a provider that defers work must
 *       capture plain values and re-resolve later.</li>
 *   <li>{@link #payload()} - an opaque consumer extension, so a consumer can carry its own
 *       evaluation subject (a placement id, a session handle) without this class learning that
 *       domain.</li>
 * </ul>
 */
public final class FactorContext {

    @Nullable private final String param;
    @Nullable private final World world;
    @Nullable private final Store<EntityStore> store;
    @Nullable private final Ref<EntityStore> subject;
    @Nullable private final Object payload;

    private FactorContext(@Nonnull Builder b) {
        this.param = b.param;
        this.world = b.world;
        this.store = b.store;
        this.subject = b.subject;
        this.payload = b.payload;
    }

    /** The authored argument beside the factor id; opaque to everything but the id's owner. */
    @Nullable
    public String param() {
        return param;
    }

    /** The world the question is being asked in, when the call site has one. */
    @Nullable
    public World world() {
        return world;
    }

    /** The store {@link #subject()} is read through. World-thread only, never retained. */
    @Nullable
    public Store<EntityStore> store() {
        return store;
    }

    /** The entity the question is ABOUT. World-thread only, never retained. */
    @Nullable
    public Ref<EntityStore> subject() {
        return subject;
    }

    /** The consumer's own opaque extension value, or null when the call site supplied none. */
    @Nullable
    public Object payload() {
        return payload;
    }

    /** {@link #payload()} narrowed to {@code type}, or null when it is absent or another type. */
    @Nullable
    public <T> T payload(@Nonnull Class<T> type) {
        return type.isInstance(payload) ? type.cast(payload) : null;
    }

    /** True when a live store AND a valid subject are both present, i.e. an entity read can run. */
    public boolean hasLiveSubject() {
        return store != null && subject != null && subject.isValid();
    }

    /**
     * This same question re-scoped to {@code param}, every other leaf carried over unchanged.
     *
     * <p>Every evaluator that walks a LIST of authored entries needs exactly this: each entry
     * carries its own {@code Param} beside the same factor id, so the context handed to the
     * provider has to be rebuilt per entry rather than reused. {@link FactorConditions} does it per
     * condition and {@link FactorFormula} per term, both through here, so "a term's Param wins over
     * the caller's" means one thing everywhere.
     */
    @Nonnull
    public FactorContext withParam(@Nullable String param) {
        return builder()
                .param(param)
                .world(world)
                .store(store)
                .subject(subject)
                .payload(payload)
                .build();
    }

    @Nonnull
    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder; every leaf is optional, and an unset one simply reads null. */
    public static final class Builder {

        @Nullable private String param;
        @Nullable private World world;
        @Nullable private Store<EntityStore> store;
        @Nullable private Ref<EntityStore> subject;
        @Nullable private Object payload;

        private Builder() {
        }

        @Nonnull
        public Builder param(@Nullable String param) {
            this.param = param;
            return this;
        }

        @Nonnull
        public Builder world(@Nullable World world) {
            this.world = world;
            return this;
        }

        @Nonnull
        public Builder store(@Nullable Store<EntityStore> store) {
            this.store = store;
            return this;
        }

        @Nonnull
        public Builder subject(@Nullable Ref<EntityStore> subject) {
            this.subject = subject;
            return this;
        }

        @Nonnull
        public Builder payload(@Nullable Object payload) {
            this.payload = payload;
            return this;
        }

        @Nonnull
        public FactorContext build() {
            return new FactorContext(this);
        }
    }
}
