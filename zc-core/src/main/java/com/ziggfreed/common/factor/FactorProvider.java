package com.ziggfreed.common.factor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves ONE namespaced factor id to a number for the question in {@link FactorContext}.
 *
 * <p><b>{@code null} means "cannot answer"</b>, and it is the only way to say so: a provider that
 * has no live subject to read, no such stat channel, or nothing held returns null rather than
 * inventing a {@code 0}. That distinction is what makes a gate fail CLOSED - a bounds-less
 * condition passes on any real number and fails on null, so "the mod that owns this factor is not
 * installed" and "the value happens to be zero" can never be confused.
 *
 * <p>Runs synchronously on the world thread and must not retain the context past the call. A
 * provider that throws is caught by the registry and treated exactly like a null answer, so a
 * broken third-party provider can never take down a sweep, a roll, or a dialogue render.
 */
@FunctionalInterface
public interface FactorProvider {

    /** The resolved value, or {@code null} when this provider cannot answer for {@code ctx}. */
    @Nullable
    Double resolve(@Nonnull FactorContext ctx);
}
