package com.ziggfreed.common.objectives.book.achievement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.ui.kit.DetailView;
import com.ziggfreed.common.ui.kit.LedgerContext;
import com.ziggfreed.common.ui.kit.LedgerModel;
import com.ziggfreed.common.ui.kit.LedgerRow;
import com.ziggfreed.common.ui.kit.LedgerSection;
import com.ziggfreed.common.ui.kit.LedgerSource;
import com.ziggfreed.common.ui.kit.Mark;
import com.ziggfreed.common.ui.kit.Picture;
import com.ziggfreed.common.ui.kit.Tone;

/**
 * The book's Statistics view lists what every contributed source returns, in the sources' own order, and opens a
 * row's page from the source that owns it. Two sources may use the same row and section ids, so the view names each
 * by its source; a source with nothing to show adds nothing, and a view with no section at all is empty (its
 * segment hides); a source that throws costs only its own sections.
 */
class StatisticsViewTest {

    @Test
    void noSourcesIsEmpty() {
        StatisticsView view = StatisticsView.of(List.of(), source -> List.of());
        assertTrue(view.isEmpty());
        assertTrue(view.model().sections().isEmpty());
        assertNull(view.model().firstSelectable());
    }

    @Test
    void aSourceWithNoSectionAddsNothing() {
        Source quiet = new Source("seasons", Map.of());
        assertTrue(StatisticsView.of(List.of(quiet), StatisticsViewTest::read).isEmpty());
        assertTrue(StatisticsView.read(List.of(quiet), null).isEmpty(), "no one to read for reads nothing");
    }

    @Test
    void sectionsFollowTheSourcesOrderAndAreNamedByTheirSource() {
        Source mmo = new Source("mmoskilltree:statistics", Map.of("combat", List.of("kills", "deaths")));
        Source seasons = new Source("ziggfreedcommon:seasons", Map.of("combat", List.of("kills")));
        StatisticsView view = StatisticsView.of(List.of(mmo, seasons), StatisticsViewTest::read);

        assertFalse(view.isEmpty());
        LedgerModel model = view.model();
        assertEquals(2, model.sections().size());
        assertEquals(StatisticsView.id(mmo, "combat"), model.sections().get(0).id());
        assertEquals(StatisticsView.id(seasons, "combat"), model.sections().get(1).id());
        assertEquals(List.of(StatisticsView.id(mmo, "kills"), StatisticsView.id(mmo, "deaths")),
                model.sections().get(0).rows().stream().map(LedgerRow::id).toList());
        assertEquals(StatisticsView.id(seasons, "kills"), model.sections().get(1).rows().get(0).id(),
                "the same row id in two sources stays two rows");
        assertEquals(StatisticsView.id(mmo, "kills"), model.firstSelectable());
        assertEquals("Kills", plain(model.sections().get(0).rows().get(0)), "the row reads as its source wrote it");
    }

    @Test
    void aRowOpensItsPageFromTheSourceThatOwnsIt() {
        Source mmo = new Source("mmoskilltree:statistics", Map.of("combat", List.of("kills")));
        Source seasons = new Source("ziggfreedcommon:seasons", Map.of("seasons", List.of("hallows_eve")));
        StatisticsView view = StatisticsView.of(List.of(mmo, seasons), StatisticsViewTest::read);

        DetailView page = view.page(StatisticsView.id(seasons, "hallows_eve"), StatisticsViewTest::open);
        assertNotNull(page);
        assertEquals(List.of("hallows_eve"), seasons.pagesAsked, "the source hears its own row id");
        assertTrue(mmo.pagesAsked.isEmpty());

        assertSame(seasons, view.sourceOf(StatisticsView.id(seasons, "hallows_eve")));
        assertEquals("hallows_eve", view.ownId(StatisticsView.id(seasons, "hallows_eve")));
        assertNull(view.page("nobody/row", StatisticsViewTest::open), "a row no source owns has no page");
        assertNull(view.page(null, StatisticsViewTest::open));
        assertNull(view.sourceOf("ghoul_breaker_2026"), "an achievement id is no statistics row");
    }

    @Test
    void aSourceIdHoldingTheSeparatorStillRoutes() {
        Source odd = new Source("odd/source", Map.of("s", List.of("a/b")));
        StatisticsView view = StatisticsView.of(List.of(odd), StatisticsViewTest::read);
        String id = view.model().sections().get(0).rows().get(0).id();
        assertSame(odd, view.sourceOf(id));
        assertEquals("a/b", view.ownId(id));
    }

    @Test
    void aThrowingSourceCostsOnlyItsOwnSections() {
        Source good = new Source("good", Map.of("combat", List.of("kills")));
        LedgerSource bad = new LedgerSource() {
            @Nonnull
            @Override
            public String id() {
                return "bad";
            }

            @Override
            public int order() {
                return 0;
            }

            @Nonnull
            @Override
            public List<LedgerSection> sections(@Nonnull LedgerContext ctx) {
                throw new IllegalStateException("boom");
            }

            @Nullable
            @Override
            public DetailView page(@Nonnull String rowId, @Nonnull LedgerContext ctx) {
                throw new IllegalStateException("boom");
            }
        };
        StatisticsView view = StatisticsView.of(List.of(bad, good), source -> {
            if (source == bad) {
                throw new IllegalStateException("boom");
            }
            return read(source);
        });
        assertEquals(1, view.model().sections().size());
        assertEquals(StatisticsView.id(good, "combat"), view.model().sections().get(0).id());
    }

    @Nonnull
    private static List<LedgerSection> read(@Nonnull LedgerSource source) {
        return ((Source) source).sections();
    }

    @Nullable
    private static DetailView open(@Nonnull LedgerSource source, @Nonnull String rowId) {
        return ((Source) source).page(rowId);
    }

    @Nonnull
    private static String plain(@Nonnull LedgerRow row) {
        return row.title().getRawText();
    }

    /** A source that answers from a fixed table and records which pages were asked for. */
    private static final class Source implements LedgerSource {

        private final String id;
        private final Map<String, List<String>> table;
        final List<String> pagesAsked = new ArrayList<>();

        Source(@Nonnull String id, @Nonnull Map<String, List<String>> table) {
            this.id = id;
            this.table = table;
        }

        @Nonnull
        @Override
        public String id() {
            return id;
        }

        @Override
        public int order() {
            return 0;
        }

        @Nonnull
        List<LedgerSection> sections() {
            List<LedgerSection> out = new ArrayList<>();
            for (Map.Entry<String, List<String>> entry : table.entrySet()) {
                List<LedgerRow> rows = new ArrayList<>();
                for (String row : entry.getValue()) {
                    rows.add(new LedgerRow(row, Msg.raw(Character.toUpperCase(row.charAt(0)) + row.substring(1)),
                            null, Picture.NONE, Tone.NEUTRAL, null, Msg.num(3), null, Mark.NONE, false));
                }
                out.add(new LedgerSection(entry.getKey(), Msg.raw(entry.getKey()), rows, true));
            }
            return out;
        }

        @Nonnull
        @Override
        public List<LedgerSection> sections(@Nonnull LedgerContext ctx) {
            return sections();
        }

        @Nullable
        DetailView page(@Nonnull String rowId) {
            pagesAsked.add(rowId);
            return new DetailView(Picture.NONE, Msg.raw(rowId), null, null, List.of(), null, null, null, null,
                    List.of(), List.of(), null);
        }

        @Nullable
        @Override
        public DetailView page(@Nonnull String rowId, @Nonnull LedgerContext ctx) {
            return page(rowId);
        }
    }
}
