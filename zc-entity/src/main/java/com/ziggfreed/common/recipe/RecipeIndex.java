package com.ziggfreed.common.recipe;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The native RECIPE INDEX: a {@link RecipeCatalog} over the engine's {@code CraftingRecipe} store,
 * built on first read, dropped when recipes or items reload, and rebuilt on the next read.
 *
 * <p><b>Why a consumer derives from this rather than authoring rows.</b> Every native recipe is in
 * it, standalone files and item-inline recipes alike, at full quantities. So anything that derives
 * from engine recipes (a bench of its own that follows vanilla's Salvage bench, a "what does this
 * break down into" hint) covers content it has never heard of: a third party's gear that ships a
 * native salvage recipe is covered the moment its pack loads, with nothing authored for it anywhere
 * else.
 *
 * <p><b>Read one snapshot per question.</b> {@link #catalog()} hands back an immutable snapshot, so
 * several lookups made through one snapshot agree with each other even if a reload lands between
 * them. A consumer keeping a DERIVED cache of its own keys it on {@link #generation()}: the number
 * moves every time the index is invalidated, so "has anything I derived from gone stale" is one
 * comparison.
 *
 * <p><b>Built from the engine's asset map, never from its per-bench registries.</b> The engine keeps
 * per-bench recipe lists of its own, but the order in which they rebuild against another mod's
 * reload handler is not guaranteed, while the asset map is complete by the time a load event fires.
 * The library invalidates the live index on every recipe and item load and removal (an item reload
 * reloads the recipes authored inside it), so a consumer never invalidates it itself.
 *
 * <p>Thread-safe: the snapshot and the generation are atomics, a read never blocks, and a snapshot
 * built across an invalidation is handed to that one reader but never kept.
 */
public final class RecipeIndex {

    private static final RecipeIndex LIVE = new RecipeIndex(LiveRecipes::read);

    @Nonnull private final Supplier<RecipeCatalog> source;
    @Nonnull private final AtomicLong generation = new AtomicLong();
    @Nonnull private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();

    /** A catalog paired with the generation it was built at. */
    private record Snapshot(long generation, @Nonnull RecipeCatalog catalog) {
    }

    /**
     * An index over {@code source}, which builds a fresh catalog on demand, or answers null when it
     * cannot read yet (the index then answers {@link RecipeCatalog#empty()} and tries again on the
     * next read rather than keeping the failure). The live server's index is {@link #live()}; a
     * test builds its own over hand-written recipes.
     */
    public RecipeIndex(@Nonnull Supplier<RecipeCatalog> source) {
        this.source = source;
    }

    /** The server's one index over the engine's own recipe store. */
    @Nonnull
    public static RecipeIndex live() {
        return LIVE;
    }

    /** The current snapshot, built now if nothing is cached for the current generation. */
    @Nonnull
    public RecipeCatalog catalog() {
        long current = generation.get();
        Snapshot cached = snapshot.get();
        if (cached != null && cached.generation() == current) {
            return cached.catalog();
        }
        RecipeCatalog built = build();
        if (built == null) {
            return RecipeCatalog.empty();
        }
        if (generation.get() == current) {
            snapshot.set(new Snapshot(current, built));
        }
        return built;
    }

    /**
     * Drop the cached snapshot and move {@link #generation()} on, so the next read rebuilds and every
     * consumer's derived cache sees it is stale. The library calls this for the live index on every
     * recipe and item reload.
     */
    public void invalidate() {
        generation.incrementAndGet();
        snapshot.set(null);
    }

    /** A number that changes every time the index is invalidated; key a derived cache on it. */
    public long generation() {
        return generation.get();
    }

    @Nullable
    private RecipeCatalog build() {
        try {
            return source.get();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
