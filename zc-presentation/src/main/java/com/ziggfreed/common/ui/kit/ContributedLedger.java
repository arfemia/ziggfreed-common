package com.ziggfreed.common.ui.kit;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.util.SafeLog;

/**
 * What every source contributed to one {@link LedgerContributions} surface returns for one viewer, as one list, and
 * the page of one of its rows, from the source that owns it: the one reading every surface that lists another
 * module's sections shares (the book's Statistics view, the Claims tab).
 *
 * <p><b>Ids.</b> Two sources may use the same row or section id, so a surface names each by its source:
 * {@link #id(LedgerSource, String)} is the source's id, {@link #SEPARATOR}, then the source's own id. A row's page and
 * a section toggle route back through {@link #sourceOf} and {@link #ownId}.
 *
 * <p>A source that throws costs only its own sections (logged under the surface's tag); a surface where no source
 * returned a section {@link #isEmpty() is empty}.
 */
public final class ContributedLedger {

    /** Between a source's id and its own row or section id. */
    public static final String SEPARATOR = "/";

    @Nonnull private final List<LedgerSource> sources;
    @Nonnull private final List<LedgerSection> sections;
    @Nonnull private final String tag;

    private ContributedLedger(@Nonnull List<LedgerSource> sources, @Nonnull List<LedgerSection> sections,
            @Nonnull String tag) {
        this.sources = List.copyOf(sources);
        this.sections = List.copyOf(sections);
        this.tag = tag;
    }

    /**
     * Every source on {@code surface}, read for {@code ctx}; empty when it is null (a page with no player behind it).
     *
     * @param tag what a failing source's line names it by ({@code "[claims] the Claims tab's"})
     */
    @Nonnull
    public static ContributedLedger read(@Nonnull String surface, @Nullable LedgerContext ctx, @Nonnull String tag) {
        return read(LedgerContributions.sources(surface), ctx, tag);
    }

    /** {@code sources} read for {@code ctx}; empty when it is null or there are none. */
    @Nonnull
    public static ContributedLedger read(@Nonnull List<LedgerSource> sources, @Nullable LedgerContext ctx,
            @Nonnull String tag) {
        if (ctx == null || sources.isEmpty()) {
            return new ContributedLedger(sources, List.of(), tag);
        }
        return of(sources, source -> source.sections(ctx), tag);
    }

    /** {@code sources}, each read through {@code sections} (a test's own reading). */
    @Nonnull
    public static ContributedLedger of(@Nonnull List<LedgerSource> sources,
            @Nonnull Function<LedgerSource, List<LedgerSection>> sections, @Nonnull String tag) {
        List<LedgerSection> all = new ArrayList<>();
        for (LedgerSource source : sources) {
            List<LedgerSection> own;
            try {
                own = sections.apply(source);
            } catch (Throwable t) {
                SafeLog.warn(tag + " source '" + source.id() + "' failed: " + t.getMessage());
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
        return new ContributedLedger(sources, all, tag);
    }

    /** Whether no source returned a section. */
    public boolean isEmpty() {
        return sections.isEmpty();
    }

    /** Every section, named by its source, in the sources' order. */
    @Nonnull
    public List<LedgerSection> sections() {
        return sections;
    }

    /** {@code ownId} of {@code source} as a surface names it. */
    @Nonnull
    public static String id(@Nonnull LedgerSource source, @Nonnull String ownId) {
        return source.id() + SEPARATOR + ownId;
    }

    /** The source a surface id belongs to (the longest source id it starts with), or null for none. */
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

    /** The source's own id inside a surface id, or null when no source owns it. */
    @Nullable
    public String ownId(@Nullable String id) {
        LedgerSource source = sourceOf(id);
        return source == null ? null : id.substring(source.id().length() + SEPARATOR.length());
    }

    /** Does a row of this surface carry {@code id}? */
    public boolean hasRow(@Nullable String id) {
        if (id == null) {
            return false;
        }
        for (LedgerSection section : sections) {
            for (LedgerRow row : section.rows()) {
                if (row.id().equals(id)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The first row's id, or null when there is none. */
    @Nullable
    public String firstRow() {
        for (LedgerSection section : sections) {
            if (!section.rows().isEmpty()) {
                return section.rows().get(0).id();
            }
        }
        return null;
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
    public DetailView page(@Nullable String rowId, @Nonnull BiFunction<LedgerSource, String, DetailView> pages) {
        LedgerSource source = sourceOf(rowId);
        if (source == null) {
            return null;
        }
        try {
            return pages.apply(source, ownId(rowId));
        } catch (Throwable t) {
            SafeLog.warn(tag + " source '" + source.id() + "' failed on a page: " + t.getMessage());
            return null;
        }
    }

    @Nonnull
    private static LedgerSection named(@Nonnull LedgerSource source, @Nonnull LedgerSection section) {
        List<LedgerRow> rows = new ArrayList<>(section.rows().size());
        for (LedgerRow row : section.rows()) {
            rows.add(new LedgerRow(id(source, row.id()), row.title(), row.meta(), row.picture(), row.tone(),
                    row.state(), row.value(), row.progress(), row.mark(), row.faint(), row.metaGlyph(),
                    row.accent()));
        }
        return new LedgerSection(id(source, section.id()), section.label(), rows, section.openByDefault(),
                section.cap());
    }
}
