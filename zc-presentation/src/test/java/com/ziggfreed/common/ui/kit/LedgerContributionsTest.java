package com.ziggfreed.common.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The one contribution seam: sources read by order then id, the same id contributed again replaces the first, each
 * surface keeps its own sources, the reading is a copy, and a reset forgets everything.
 */
class LedgerContributionsTest {

    @BeforeEach
    @AfterEach
    void reset() {
        LedgerContributions.resetForTests();
    }

    @Test
    void sourcesReadByOrderThenId() {
        LedgerContributions.contribute(LedgerContributions.STATISTICS, new Source("seasons", 20));
        LedgerContributions.contribute(LedgerContributions.STATISTICS, new Source("mmo", 10));
        LedgerContributions.contribute(LedgerContributions.STATISTICS, new Source("alpha", 20));
        assertEquals(List.of("mmo", "alpha", "seasons"), ids(LedgerContributions.STATISTICS));
    }

    @Test
    void theSameIdReplacesTheFirst() {
        Source first = new Source("mmo", 10);
        Source again = new Source("mmo", 5);
        LedgerContributions.contribute(LedgerContributions.STATISTICS, first);
        LedgerContributions.contribute(LedgerContributions.STATISTICS, again);
        List<LedgerSource> sources = LedgerContributions.sources(LedgerContributions.STATISTICS);
        assertEquals(1, sources.size());
        assertSame(again, sources.get(0));
    }

    @Test
    void eachSurfaceKeepsItsOwn() {
        LedgerContributions.contribute(LedgerContributions.STATISTICS, new Source("mmo", 10));
        LedgerContributions.contribute("other:surface", new Source("x", 1));
        assertEquals(List.of("mmo"), ids(LedgerContributions.STATISTICS));
        assertEquals(List.of("x"), ids("other:surface"));
        assertTrue(LedgerContributions.sources("nobody:here").isEmpty());
    }

    @Test
    void theReadingIsACopy() {
        LedgerContributions.contribute(LedgerContributions.STATISTICS, new Source("mmo", 10));
        List<LedgerSource> sources = LedgerContributions.sources(LedgerContributions.STATISTICS);
        assertThrows(UnsupportedOperationException.class, () -> sources.add(new Source("y", 1)));
    }

    @Test
    void aResetForgetsEverything() {
        LedgerContributions.contribute(LedgerContributions.STATISTICS, new Source("mmo", 10));
        LedgerContributions.resetForTests();
        assertTrue(LedgerContributions.sources(LedgerContributions.STATISTICS).isEmpty());
    }

    @Test
    void theStatisticsSurfaceIsNamespaced() {
        assertEquals("ziggfreedcommon:statistics", LedgerContributions.STATISTICS);
    }

    @Nonnull
    private static List<String> ids(@Nonnull String surface) {
        return LedgerContributions.sources(surface).stream().map(LedgerSource::id).toList();
    }

    private record Source(String id, int order) implements LedgerSource {

        @Nonnull
        @Override
        public List<LedgerSection> sections(@Nonnull LedgerContext ctx) {
            return List.of();
        }

        @Override
        public DetailView page(@Nonnull String rowId, @Nonnull LedgerContext ctx) {
            return null;
        }
    }
}
