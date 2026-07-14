package com.ziggfreed.common.cast;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Immutable, FIELD-ADDITIVE carrier passed to a {@link HitAction} when a resolved hit lands. The
 * evolvable replacement for a raw {@code BiConsumer<Store, Ref>} on-hit callback: new context fields
 * are added over time WITHOUT breaking a {@code HitAction} implementer (a widened JDK
 * {@code BiConsumer} generic parameter would break every implementer; a new builder field here does
 * not).
 *
 * <h2>Why a {@link ComponentAccessor} and not a {@code Store}</h2>
 * <p>The accessor is typed {@link ComponentAccessor}{@code <EntityStore>}, the interface BOTH
 * {@code Store} AND {@code CommandBuffer} implement. A tick-driven consumer that must DEFER a
 * component mutation (an "apply effect on hit" that adds a component, which a live {@code Store}
 * rejects mid-tick under the processing lock) supplies a {@code CommandBuffer} accessor here; a
 * synchronous consumer supplies the live {@code Store}. The raw {@code BiConsumer<Store, Ref>} shape
 * could carry only a {@code Store}, which closed off the deferred-mutation path entirely - this
 * carrier reopens it.
 *
 * <p>Every field beyond {@code accessor} + {@code target} is optional (nullable / zero default): a
 * consumer populates what its actions need and leaves the rest unset. Build via {@link #builder()}.
 */
public final class HitContext {

    @Nonnull private final ComponentAccessor<EntityStore> accessor;
    @Nonnull private final Ref<EntityStore> target;
    @Nullable private final Ref<EntityStore> source;
    @Nullable private final UUID sourcePlayerId;
    private final double damageAmount;
    @Nullable private final Vector3d position;
    @Nullable private final String cause;

    private HitContext(@Nonnull Builder b) {
        this.accessor = b.accessor;
        this.target = b.target;
        this.source = b.source;
        this.sourcePlayerId = b.sourcePlayerId;
        this.damageAmount = b.damageAmount;
        this.position = b.position;
        this.cause = b.cause;
    }

    /** The component accessor for the hit - a live {@code Store} OR a {@code CommandBuffer}. */
    @Nonnull
    public ComponentAccessor<EntityStore> accessor() {
        return accessor;
    }

    /** The entity that was hit. */
    @Nonnull
    public Ref<EntityStore> target() {
        return target;
    }

    /** The source / attacker entity, or {@code null} when detached / unknown. */
    @Nullable
    public Ref<EntityStore> source() {
        return source;
    }

    /** The source player's UUID, or {@code null} when the source is not a player. */
    @Nullable
    public UUID sourcePlayerId() {
        return sourcePlayerId;
    }

    /** The damage amount associated with this hit (0.0 when not applicable). */
    public double damageAmount() {
        return damageAmount;
    }

    /** The hit position, or {@code null} when not resolved. */
    @Nullable
    public Vector3d position() {
        return position;
    }

    /** An opaque cause label, or {@code null} when not set. */
    @Nullable
    public String cause() {
        return cause;
    }

    @Nonnull
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for a {@link HitContext}. New optional fields are added here without breaking
     * a {@code HitAction} implementer.
     */
    public static final class Builder {
        private ComponentAccessor<EntityStore> accessor;
        private Ref<EntityStore> target;
        @Nullable private Ref<EntityStore> source;
        @Nullable private UUID sourcePlayerId;
        private double damageAmount;
        @Nullable private Vector3d position;
        @Nullable private String cause;

        /** The component accessor - a live {@code Store} or a {@code CommandBuffer}. Required. */
        @Nonnull
        public Builder accessor(@Nonnull ComponentAccessor<EntityStore> accessor) {
            this.accessor = accessor;
            return this;
        }

        /** The hit target. Required. */
        @Nonnull
        public Builder target(@Nonnull Ref<EntityStore> target) {
            this.target = target;
            return this;
        }

        @Nonnull
        public Builder source(@Nullable Ref<EntityStore> source) {
            this.source = source;
            return this;
        }

        @Nonnull
        public Builder sourcePlayerId(@Nullable UUID sourcePlayerId) {
            this.sourcePlayerId = sourcePlayerId;
            return this;
        }

        @Nonnull
        public Builder damageAmount(double damageAmount) {
            this.damageAmount = damageAmount;
            return this;
        }

        @Nonnull
        public Builder position(@Nullable Vector3d position) {
            this.position = position;
            return this;
        }

        @Nonnull
        public Builder cause(@Nullable String cause) {
            this.cause = cause;
            return this;
        }

        @Nonnull
        public HitContext build() {
            return new HitContext(this);
        }
    }
}
