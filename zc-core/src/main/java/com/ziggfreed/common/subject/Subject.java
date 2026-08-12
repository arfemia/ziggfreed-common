package com.ziggfreed.common.subject;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Who an engine operation is about: a stable id, a display name, and an OPAQUE handle the consumer
 * attached.
 *
 * <p>ONE identity vocabulary for every engine in this library. A quest accept, an achievement
 * criterion, a counter tally, a loot grant and a gate evaluation are all about the same someone,
 * and they all say so with this record - so a handler written for one engine reads naturally when
 * another engine calls it, and no engine has to learn a peer's noun.
 *
 * <p>The handle is how those engines stay free of any particular player representation. A consumer
 * puts whatever it needs to act on the player in there - an engine player reference, an entity
 * handle, a session object - and gets it back, typed, inside its own store, probes, gates, and
 * reward handlers via {@link #handleAs}. Nothing in any engine ever looks inside it.
 *
 * <p>Cheap to build and immutable, so constructing one per call is fine.
 */
public record Subject(@Nonnull UUID id, @Nonnull String name, @Nullable Object handle) {

    /** A handle-less subject: enough for the pure state operations, not for granting or taking items. */
    @Nonnull
    public static Subject of(@Nonnull UUID id, @Nonnull String name) {
        return new Subject(id, name, null);
    }

    /**
     * The attached handle cast to {@code type}, or null when there is none or it is something else.
     * The one read path, so a consumer never has to write an unchecked cast of its own.
     */
    @Nullable
    public <T> T handleAs(@Nonnull Class<T> type) {
        return type.isInstance(handle) ? type.cast(handle) : null;
    }
}
