package com.ziggfreed.common.objectives.book.achievement;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.ui.kit.DetailView;
import com.ziggfreed.common.ui.kit.LedgerContext;
import com.ziggfreed.common.ui.kit.LedgerContributions;
import com.ziggfreed.common.ui.kit.LedgerModel;
import com.ziggfreed.common.ui.kit.LedgerRow;
import com.ziggfreed.common.ui.kit.LedgerSection;
import com.ziggfreed.common.ui.kit.LedgerSource;
import com.ziggfreed.common.util.SafeLog;

/**
 * The book's Statistics view as data: every section the sources contributed to
 * {@link LedgerContributions#STATISTICS} return for one viewer, in the sources' own order, as one list; and the page
 * of a row, from the source that owns it. No zc module knows what a statistic is: the MMO contributes its counters,
 * zc-almanac its seasons, and the book only lists and opens what they hand over.
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
    public static final String SEPARATOR = "/";

    /** No sources, or no one to read for. */
    public static final StatisticsView EMPTY = new StatisticsView(List.of(), LedgerModel.of(List.of()));

    private final List<LedgerSource> sources;
    private final LedgerModel model;

    private StatisticsView(@Nonnull List<LedgerSource> sources, @Nonnull LedgerModel model) {
        this.sources = List.copyOf(sources);
        this.model = model;
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
        return of(sources, source -> source.sections(ctx));
    }

    /** {@code sources}, each read through {@code sections} (a test's own reading). */
    @Nonnull
    static StatisticsView of(@Nonnull List<LedgerSource> sources,
            @Nonnull Function<LedgerSource, List<LedgerSection>> sections) {
        List<LedgerSection> all = new ArrayList<>();
        for (LedgerSource source : sources) {
            List<LedgerSection> own;
            try {
                own = sections.apply(source);
            } catch (Throwable t) {
                SafeLog.warn("[progression] the book's statistics source '" + source.id() + "' failed: "
                        + t.getMessage());
                continue;
            }
            if (own == null) {
                continue;
            }
            for (LedgerSection section : own) {
                if (section != null) {
                    all.add(named(source, section));
                }
            }
        }
        return all.isEmpty() ? new StatisticsView(sources, LedgerModel.of(List.of()))
                : new StatisticsView(sources, LedgerModel.of(all));
    }

    /** Whether no source returned a section: the view has nothing to list and its segment hides. */
    public boolean isEmpty() {
        return model.sections().isEmpty();
    }

    /** Every section, named by its source. */
    @Nonnull
    public LedgerModel model() {
        return model;
    }

    /** {@code ownId} of {@code source} as this view names it. */
    @Nonnull
    public static String id(@Nonnull LedgerSource source, @Nonnull String ownId) {
        return source.id() + SEPARATOR + ownId;
    }

    /** The source a view id belongs to (the longest source id it starts with), or null for none. */
    @Nullable
    public LedgerSource sourceOf(@Nullable String id) {
        if (id == null) {
            return null;
        }
        LedgerSource best = null;
        for (LedgerSource source : sources) {
            String prefix = source.id() + SEPARATOR;
            if (id.startsWith(prefix) && (best == null || source.id().length() > best.id().length())) {
                best = source;
            }
        }
        return best;
    }

    /** The source's own id inside a view id, or null when no source owns it. */
    @Nullable
    public String ownId(@Nullable String id) {
        LedgerSource source = sourceOf(id);
        return source == null ? null : id.substring(source.id().length() + SEPARATOR.length());
    }

    /** The page for a row, from the source that owns it; null for no such row, nobody to read for, or a failure. */
    @Nullable
    public DetailView page(@Nullable String rowId, @Nullable LedgerContext ctx) {
        if (ctx == null) {
            return null;
        }
        return page(rowId, (source, own) -> source.page(own, ctx));
    }

    /** {@link #page(String, LedgerContext)} through {@code pages} (a test's own reading). */
    @Nullable
    DetailView page(@Nullable String rowId, @Nonnull BiFunction<LedgerSource, String, DetailView> pages) {
        LedgerSource source = sourceOf(rowId);
        if (source == null) {
            return null;
        }
        try {
            return pages.apply(source, ownId(rowId));
        } catch (Throwable t) {
            SafeLog.warn("[progression] the book's statistics source '" + source.id() + "' failed on a page: "
                    + t.getMessage());
            return null;
        }
    }

    @Nonnull
    private static LedgerSection named(@Nonnull LedgerSource source, @Nonnull LedgerSection section) {
        List<LedgerRow> rows = new ArrayList<>(section.rows().size());
        for (LedgerRow row : section.rows()) {
            rows.add(new LedgerRow(id(source, row.id()), row.title(), row.meta(), row.picture(), row.tone(),
                    row.state(), row.value(), row.progress(), row.mark(), row.faint()));
        }
        return new LedgerSection(id(source, section.id()), section.label(), rows, section.openByDefault(),
                section.cap());
    }
}
