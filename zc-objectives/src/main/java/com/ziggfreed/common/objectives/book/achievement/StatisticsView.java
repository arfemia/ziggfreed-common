package com.ziggfreed.common.objectives.book.achievement;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.ui.kit.ContributedLedger;
import com.ziggfreed.common.ui.kit.DetailView;
import com.ziggfreed.common.ui.kit.LedgerContext;
import com.ziggfreed.common.ui.kit.LedgerContributions;
import com.ziggfreed.common.ui.kit.LedgerModel;
import com.ziggfreed.common.ui.kit.LedgerSection;
import com.ziggfreed.common.ui.kit.LedgerSource;

/**
 * The book's Statistics view as data: every section the sources contributed to
 * {@link LedgerContributions#STATISTICS} return for one viewer, in the sources' own order, as one list; and the page
 * of a row, from the source that owns it. No zc module knows what a statistic is: the MMO contributes its counters,
 * zc-almanac its seasons, and the book only lists and opens what they hand over. The reading is the kit's one
 * {@link ContributedLedger}, which the Claims tab shares.
 *
 * <p><b>Ids.</b> Two sources may use the same row or section id, so the view names each by its source:
 * {@link #id(LedgerSource, String)} is the source's id, {@link #SEPARATOR}, then the source's own id. A row's page,
 * the line a page offers to open, and a section toggle all route back through {@link #sourceOf} and {@link #ownId}.
 *
 * <p>A source that throws costs only its own sections (logged); a view where no source returned a section
 * {@link #isEmpty() is empty}, and the tab hides its Statistics segment.
 */
public final class StatisticsView {

    /** Between a source's id and its own row or section id. */
    public static final String SEPARATOR = ContributedLedger.SEPARATOR;

    /** What a failing source's line names it by. */
    private static final String TAG = "[progression] the book's statistics";

    /** No sources, or no one to read for. */
    public static final StatisticsView EMPTY = new StatisticsView(ContributedLedger.of(List.of(), s -> List.of(),
            TAG));

    private final ContributedLedger ledger;
    private final LedgerModel model;

    private StatisticsView(@Nonnull ContributedLedger ledger) {
        this.ledger = ledger;
        this.model = LedgerModel.of(ledger.sections());
    }

    /** Every source on {@link LedgerContributions#STATISTICS}, read for {@code ctx}; empty when it is null. */
    @Nonnull
    public static StatisticsView read(@Nullable LedgerContext ctx) {
        return read(LedgerContributions.sources(LedgerContributions.STATISTICS), ctx);
    }

    /** {@code sources} read for {@code ctx}; empty when it is null (a page with no player behind it). */
    @Nonnull
    public static StatisticsView read(@Nonnull List<LedgerSource> sources, @Nullable LedgerContext ctx) {
        if (ctx == null || sources.isEmpty()) {
            return EMPTY;
        }
        return new StatisticsView(ContributedLedger.read(sources, ctx, TAG));
    }

    /** {@code sources}, each read through {@code sections} (a test's own reading). */
    @Nonnull
    static StatisticsView of(@Nonnull List<LedgerSource> sources,
            @Nonnull Function<LedgerSource, List<LedgerSection>> sections) {
        return new StatisticsView(ContributedLedger.of(sources, sections, TAG));
    }

    /** Whether no source returned a section: the view has nothing to list and its segment hides. */
    public boolean isEmpty() {
        return ledger.isEmpty();
    }

    /** Every section, named by its source. */
    @Nonnull
    public LedgerModel model() {
        return model;
    }

    /** {@code ownId} of {@code source} as this view names it. */
    @Nonnull
    public static String id(@Nonnull LedgerSource source, @Nonnull String ownId) {
        return ContributedLedger.id(source, ownId);
    }

    /** The source a view id belongs to (the longest source id it starts with), or null for none. */
    @Nullable
    public LedgerSource sourceOf(@Nullable String id) {
        return ledger.sourceOf(id);
    }

    /** The source's own id inside a view id, or null when no source owns it. */
    @Nullable
    public String ownId(@Nullable String id) {
        return ledger.ownId(id);
    }

    /** The page for a row, from the source that owns it; null for no such row, nobody to read for, or a failure. */
    @Nullable
    public DetailView page(@Nullable String rowId, @Nullable LedgerContext ctx) {
        return ledger.page(rowId, ctx);
    }

    /** {@link #page(String, LedgerContext)} through {@code pages} (a test's own reading). */
    @Nullable
    DetailView page(@Nullable String rowId, @Nonnull BiFunction<LedgerSource, String, DetailView> pages) {
        return ledger.page(rowId, pages);
    }
}
