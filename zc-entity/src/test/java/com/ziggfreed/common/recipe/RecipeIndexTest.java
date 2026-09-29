package com.ziggfreed.common.recipe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * The index's lifecycle over an injected source: built once on first read, kept until invalidated,
 * rebuilt on the next read after, with the generation a derived cache keys on moving each time.
 */
class RecipeIndexTest {

    private static RecipeCatalog oneRecipe(String id) {
        return RecipeCatalog.of(List.of(new NativeRecipe(id, List.of(), List.of(), null, List.of(), 0f, null)),
                List.of());
    }

    @Test
    void aSnapshotIsBuiltOnceAndKeptUntilInvalidated() {
        AtomicInteger builds = new AtomicInteger();
        RecipeIndex index = new RecipeIndex(() -> oneRecipe("Test_Recipe_" + builds.incrementAndGet()));

        RecipeCatalog first = index.catalog();
        assertSame(first, index.catalog(), "a second read reuses the snapshot");
        assertEquals(1, builds.get());

        index.invalidate();
        RecipeCatalog second = index.catalog();
        assertEquals(2, builds.get(), "the read after an invalidation rebuilds");
        assertEquals("Test_Recipe_2", second.all().get(0).id());
    }

    @Test
    void theGenerationMovesOnEveryInvalidation() {
        RecipeIndex index = new RecipeIndex(() -> oneRecipe("Test_Recipe"));
        long before = index.generation();

        index.catalog();
        assertEquals(before, index.generation(), "reading never moves the generation");

        index.invalidate();
        long after = index.generation();
        assertNotEquals(before, after);
        index.invalidate();
        assertNotEquals(after, index.generation());
    }

    @Test
    void aSourceThatCannotReadYetIsTriedAgainRatherThanKept() {
        AtomicInteger calls = new AtomicInteger();
        RecipeIndex index = new RecipeIndex(() -> calls.incrementAndGet() == 1 ? null : oneRecipe("Test_Recipe"));

        assertTrue(index.catalog().all().isEmpty(), "an unreadable source reads as empty");
        assertEquals(1, index.catalog().size(), "and is asked again on the next read");
    }

    @Test
    void aSourceThatThrowsReadsAsEmpty() {
        RecipeIndex index = new RecipeIndex(() -> {
            throw new IllegalStateException("store not registered");
        });

        assertSame(RecipeCatalog.empty(), index.catalog());
    }

    @Test
    void theLiveIndexIsOneInstanceAndReadsEmptyWithNoEngineStores() {
        assertSame(RecipeIndex.live(), RecipeIndex.live());
        assertEquals(0, RecipeIndex.live().catalog().size(), "a unit JVM has no recipe store to read");
    }
}
