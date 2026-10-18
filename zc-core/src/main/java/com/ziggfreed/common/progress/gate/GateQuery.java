package com.ziggfreed.common.progress.gate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorRegistry;
import com.ziggfreed.common.subject.Subject;

/**
 * One question put to a {@code Requires} block ({@link GateEvaluator#check}): who is asking, plus whatever the
 * moment knows that a reading of the subject alone would not (the held item, the item the moment is about,
 * the entity it happened to, the block it happened at), and an optional local factor vocabulary.
 *
 * <ul>
 *   <li>{@code subject}: who the block is asked about. Unset, it is read off the context's player when a
 *       {@code Quests} leaf or a {@code Custom} kind needs one; with neither, those leaves refuse.</li>
 *   <li>{@code context}: a factor context the caller already built for the moment (a loot pass's). Unset,
 *       the evaluator builds one from the subject through its own context seam.</li>
 *   <li>{@code held}, {@code item}, {@code target}, {@code blockId}: laid over that context leaf by leaf,
 *       each only when set (see {@link FactorContext}).</li>
 *   <li>{@code local}: a vocabulary that answers first for the ids it registers itself (a work session's
 *       readings), every other id falling through to the evaluator's own. A check carrying one is never
 *       kept between asks, since a local reading belongs to its moment.</li>
 * </ul>
 *
 * <p>Immutable; build one per question.
 */
public final class GateQuery {

    /** Asks about nobody: every reading of a player answers nothing, so a block asking for one refuses. */
    public static final GateQuery NOBODY = builder().build();

    @Nullable private final Subject subject;
    @Nullable private final FactorContext context;
    @Nullable private final ItemStack held;
    @Nullable private final ItemStack item;
    @Nullable private final Ref<EntityStore> target;
    @Nullable private final String blockId;
    @Nullable private final FactorRegistry local;

    private GateQuery(@Nonnull Builder b) {
        this.subject = b.subject;
        this.context = b.context;
        this.held = b.held;
        this.item = b.item;
        this.target = b.target;
        this.blockId = b.blockId == null || b.blockId.isBlank() ? null : b.blockId.trim();
        this.local = b.local;
    }

    /** The block asked about {@code subject}, nothing else known. */
    @Nonnull
    public static GateQuery of(@Nonnull Subject subject) {
        return builder().subject(subject).build();
    }

    /** The block asked against a context the caller already built (its player is the subject). */
    @Nonnull
    public static GateQuery of(@Nonnull FactorContext context) {
        return builder().context(context).build();
    }

    @Nonnull
    public static Builder builder() {
        return new Builder();
    }

    @Nullable
    public Subject subject() {
        return subject;
    }

    @Nullable
    public FactorContext context() {
        return context;
    }

    @Nullable
    public ItemStack held() {
        return held;
    }

    @Nullable
    public ItemStack item() {
        return item;
    }

    @Nullable
    public Ref<EntityStore> target() {
        return target;
    }

    @Nullable
    public String blockId() {
        return blockId;
    }

    @Nullable
    public FactorRegistry local() {
        return local;
    }

    /** True when the query lays any moment leaf over its context. */
    boolean laysLeaves() {
        return held != null || item != null || target != null || blockId != null;
    }

    /** {@code base} with this query's moment leaves laid over it, each only where set. */
    @Nonnull
    FactorContext over(@Nonnull FactorContext base) {
        if (!laysLeaves()) {
            return base;
        }
        FactorContext.Builder b = base.toBuilder();
        if (held != null) {
            b.held(held);
        }
        if (item != null) {
            b.item(item);
        }
        if (target != null && target.isValid()) {
            b.target(target);
        }
        if (blockId != null) {
            b.blockId(blockId);
        }
        return b.build();
    }

    /** Fluent builder; every leaf optional. */
    public static final class Builder {

        @Nullable private Subject subject;
        @Nullable private FactorContext context;
        @Nullable private ItemStack held;
        @Nullable private ItemStack item;
        @Nullable private Ref<EntityStore> target;
        @Nullable private String blockId;
        @Nullable private FactorRegistry local;

        private Builder() {
        }

        @Nonnull
        public Builder subject(@Nullable Subject subject) {
            this.subject = subject;
            return this;
        }

        @Nonnull
        public Builder context(@Nullable FactorContext context) {
            this.context = context;
            return this;
        }

        /** The stack standing in for the hand (a use the engine is about to answer). */
        @Nonnull
        public Builder held(@Nullable ItemStack held) {
            this.held = held;
            return this;
        }

        /** The item the moment is about. */
        @Nonnull
        public Builder item(@Nullable ItemStack item) {
            this.item = item;
            return this;
        }

        /** The entity the moment happened to. */
        @Nonnull
        public Builder target(@Nullable Ref<EntityStore> target) {
            this.target = target;
            return this;
        }

        /** The block type the moment is about, by asset id. */
        @Nonnull
        public Builder block(@Nullable String blockId) {
            this.blockId = blockId;
            return this;
        }

        /** A vocabulary answering first for the ids it registers itself. */
        @Nonnull
        public Builder local(@Nullable FactorRegistry local) {
            this.local = local;
            return this;
        }

        @Nonnull
        public GateQuery build() {
            return new GateQuery(this);
        }
    }
}
