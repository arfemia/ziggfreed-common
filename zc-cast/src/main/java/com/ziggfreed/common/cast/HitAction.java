package com.ziggfreed.common.cast;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A post-hit callback over a {@link HitContext}. The evolvable successor to a raw
 * {@code BiConsumer<Store, Ref>} on-hit hook: because the whole context is one field-additive
 * {@link HitContext}, a new hit field never changes this SAM's signature, so an implementer never
 * breaks when the carrier grows (unlike widening a JDK {@code BiConsumer}'s generic parameters).
 *
 * <p>Carries a {@link com.hypixel.hytale.component.ComponentAccessor} (so a deferred, command-buffer
 * mutation fits alongside a synchronous store one) plus the source / target / amount / position of
 * the hit.
 */
@FunctionalInterface
public interface HitAction {

    /** The shared no-op action - fires nothing. */
    @Nonnull
    HitAction NO_OP = ctx -> { };

    /** Run against a landed hit. */
    void onHit(@Nonnull HitContext ctx);

    /**
     * Compose this action with {@code next} so both fire on the same context, this one first. A
     * {@code null} or {@link #NO_OP} operand is dropped, so a chain never dead-links.
     */
    @Nonnull
    default HitAction andThen(@Nullable HitAction next) {
        if (next == null || next == NO_OP) return this;
        if (this == NO_OP) return next;
        return ctx -> {
            this.onHit(ctx);
            next.onHit(ctx);
        };
    }
}
