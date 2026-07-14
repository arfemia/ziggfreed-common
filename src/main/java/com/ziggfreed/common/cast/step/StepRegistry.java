package com.ziggfreed.common.cast.step;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Maps a step key to the {@link StepHandler} that executes it. The generic register / get
 * mechanism the {@link CastKernel} dispatches through. Ships EMPTY - a consumer news up its
 * own registry and registers every handler it ships; common bakes in no keys and no handlers.
 *
 * <p><b>Per-consumer instance:</b> like {@link com.ziggfreed.common.cast.OnHitRegistry}, this is
 * an INSTANTIABLE registry (no static singleton), so two mods sharing this jar never see each
 * other's handler registrations. A consumer that wants a shared production registry holds its
 * own reference to one instance.
 *
 * <p><b>Backing map:</b> the no-arg constructor backs the registry with a {@link ConcurrentHashMap}
 * (the safe default, matching {@code OnHitRegistry}). A consumer keying by an {@code enum} that
 * wants an {@link java.util.EnumMap} passes it to the protected {@link #StepRegistry(Map)}
 * constructor from a subclass - {@code register} runs at setup and {@code get} runs after, so a
 * non-concurrent {@code EnumMap} is safe under that discipline.
 *
 * <p>{@code register} is last-write-wins (idempotent on a reload that re-registers the same
 * handlers); {@code get} returns {@code null} for an unregistered key (including a {@code null}
 * key, which never throws even on a {@link ConcurrentHashMap} backing).
 *
 * @param <K> the step key type (a consumer's discriminator, e.g. an enum)
 * @param <C> the cast context type
 * @param <S> the step type
 * @param <R> the result type
 */
public class StepRegistry<K, C, S, R> {

    private final Map<K, StepHandler<C, S, R>> handlers;

    /** New registry backed by a {@link ConcurrentHashMap}. */
    public StepRegistry() {
        this.handlers = new ConcurrentHashMap<>();
    }

    /**
     * New registry over a consumer-supplied backing map (e.g. an {@link java.util.EnumMap} for an
     * enum key). The map must be empty; the registry takes ownership of it.
     */
    protected StepRegistry(@Nonnull Map<K, StepHandler<C, S, R>> backing) {
        this.handlers = backing;
    }

    /** Register (or replace) the handler for {@code key}. Last write wins. */
    public void register(@Nonnull K key, @Nonnull StepHandler<C, S, R> handler) {
        handlers.put(key, handler);
    }

    /** The handler for {@code key}, or {@code null} when none is registered (or {@code key} is null). */
    @Nullable
    public StepHandler<C, S, R> get(@Nullable K key) {
        if (key == null) return null;
        return handlers.get(key);
    }
}
