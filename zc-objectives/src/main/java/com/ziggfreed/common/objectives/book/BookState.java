package com.ziggfreed.common.objectives.book;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.ui.builder.EventData;

import com.ziggfreed.common.ui.kit.LedgerPainter;
import com.ziggfreed.common.ui.kit.LedgerSection;

/**
 * Everything the Objective Book shows, as one value: which tab, which view of it, the filters, the selected
 * row and the sections the player opened or closed by hand. Every binding carries all of it
 * ({@link #event}), the page reopens on it, and an open from outside the book starts from it
 * ({@link #opening}), so no click ever loses what the player had narrowed the screen to.
 *
 * <p><b>Filters.</b> {@link #ALL} is "no filter" for the category, status and tag; {@link #SORT_DEFAULT} is the
 * sort's default, a real id so a dropdown never shows blank. A blank value reads as that default, which is
 * also how an event from the pre-redesign bindings (a blank sort or tag) decodes.
 *
 * <p><b>Sections.</b> {@link #openSections} holds only what the player said by hand, in the kit's own form: a
 * section id means opened, {@code "!" + id} closed ({@link LedgerPainter#withSection}). A section never touched
 * follows its own {@code openByDefault} ({@link #isOpen}), so a fresh book opens Ready, In progress and
 * Available and leaves the rest closed without the state knowing which sections exist; a tab hands the set to
 * {@link LedgerPainter#paint} unchanged.
 */
public record BookState(@Nonnull String tab, @Nonnull String view, @Nonnull String category, @Nonnull String status,
        @Nonnull String sort, @Nonnull String search, @Nonnull String tag, @Nullable String selectedId,
        @Nonnull Set<String> openSections) {

    /** Achievements' landing view: the hero, the next milestone, the category tiles and the strips. */
    public static final String VIEW_OVERVIEW = "overview";

    /** A list and the selected row's page; the quest journal's only view. */
    public static final String VIEW_BROWSE = "browse";

    /** The contributed statistics ({@code LedgerContributions.STATISTICS}). */
    public static final String VIEW_STATISTICS = "statistics";

    /** The category, status or tag value meaning "no filter". */
    public static final String ALL = "all";

    /** The sort's default id. */
    public static final String SORT_DEFAULT = "default";

    /** The prefix that marks a section closed by hand in {@link #openSections} (the kit's own mark). */
    public static final String CLOSED = LedgerPainter.CLOSED;

    /** The keys a binding carries; the first eight are the pre-redesign book's own. */
    public static final String KEY_ACTION = "Action";
    public static final String KEY_TAB = "Tab";
    public static final String KEY_ID = "Id";
    public static final String KEY_CATEGORY = "Category";
    public static final String KEY_STATUS = "Status";
    public static final String KEY_SORT = "Sort";
    public static final String KEY_SEARCH = "Search";
    public static final String KEY_TAG = "Tag";
    public static final String KEY_VIEW = "View";
    public static final String KEY_SELECTED = "Selected";
    public static final String KEY_OPEN_SECTIONS = "OpenSections";

    /** A section toggle's own payload: which section, and whether the click opens it ({@code "true"}) or closes it. */
    public static final String KEY_SECTION = "Section";
    public static final String KEY_OPEN = "Open";

    /**
     * The key the live search field rides under on every binding ({@code ZigSearchRow.carry}); the {@code @}
     * makes the client resolve it as an element path, and the codec declares the same key.
     */
    public static final String KEY_SEARCH_INPUT = "@SearchInput";

    /** What separates the ids in {@link #KEY_OPEN_SECTIONS}. */
    private static final String SECTION_SEPARATOR = ",";

    public BookState {
        tab = normalizeTab(tab);
        view = blank(view) ? defaultView(tab) : view.trim().toLowerCase(Locale.ROOT);
        category = orDefault(category, ALL);
        status = orDefault(status, ALL);
        sort = orDefault(sort, SORT_DEFAULT);
        search = search == null ? "" : search.trim();
        tag = orDefault(tag, ALL);
        selectedId = blank(selectedId) ? null : selectedId.trim();
        openSections = openSections == null ? Set.of() : Collections.unmodifiableSet(new TreeSet<>(cleanIds(openSections)));
    }

    /** A fresh book on {@code tab} (quests when null or unknown), every filter off, on the tab's default view. */
    @Nonnull
    public static BookState of(@Nullable String tab) {
        return new BookState(tab, null, null, null, null, null, null, null, Set.of());
    }

    /**
     * Where an open from outside the book starts: {@code tab} with {@code selectedId} chosen, in the view that
     * shows a selected row (Achievements' overview shows no page, so a selection opens Browse). No selection is
     * {@link #of}.
     */
    @Nonnull
    public static BookState opening(@Nullable String tab, @Nullable String selectedId) {
        BookState fresh = of(tab);
        return blank(selectedId) ? fresh : fresh.withSelected(selectedId).showingSelection();
    }

    /**
     * Where a focused open from outside the book starts: {@code tab} on Browse, filtered to {@code category}, with
     * {@code openSections} in the kit's own form (an id opened, {@code "!" + id} closed) and {@code selectedId} chosen
     * (null: none). Every other filter off.
     */
    @Nonnull
    public static BookState browsing(@Nullable String tab, @Nonnull String category, @Nonnull Set<String> openSections,
            @Nullable String selectedId) {
        return new BookState(tab, VIEW_BROWSE, category, null, null, null, null, selectedId, openSections);
    }

    /**
     * The state a binding carried, as the codec decoded it. The live search field ({@code @SearchInput}) wins over
     * the carried search, as it always has: it holds what the player typed, submitted or not. A key the event
     * lacks reads as its default, so an event from the pre-redesign bindings decodes too.
     */
    @Nonnull
    public static BookState decode(@Nonnull ObjectiveBookEventData data) {
        return new BookState(data.tab, data.view, data.category, data.status, data.sort,
                data.searchInput != null ? data.searchInput : data.search, data.tag, data.selected,
                splitSections(data.openSections));
    }

    /**
     * {@code action} carrying the whole state, so any binding round-trips it ({@link #decode}). A binding that
     * names a row adds {@link #KEY_ID}; the page's context adds the live search field ({@code BookContext.binding}).
     */
    @Nonnull
    public EventData event(@Nonnull String action) {
        EventData data = EventData.of(KEY_ACTION, action)
                .append(KEY_TAB, tab)
                .append(KEY_VIEW, view)
                .append(KEY_CATEGORY, category)
                .append(KEY_STATUS, status)
                .append(KEY_SORT, sort)
                .append(KEY_SEARCH, search)
                .append(KEY_TAG, tag);
        if (selectedId != null) {
            data.append(KEY_SELECTED, selectedId);
        }
        if (!openSections.isEmpty()) {
            data.append(KEY_OPEN_SECTIONS, String.join(SECTION_SEPARATOR, openSections));
        }
        return data;
    }

    // ==================== withers ====================

    /** This state with {@code id} selected (null or blank selects nothing); every other field kept. */
    @Nonnull
    public BookState withSelected(@Nullable String id) {
        return new BookState(tab, view, category, status, sort, search, tag, id, openSections);
    }

    /** This state on {@code view} (blank is the tab's default); the selection and filters kept. */
    @Nonnull
    public BookState withView(@Nullable String view) {
        return new BookState(tab, view, category, status, sort, search, tag, selectedId, openSections);
    }

    /**
     * This state with the section {@code id} opened or closed by hand; the last word on a section wins. The record
     * is the kit's ({@link LedgerPainter#withSection}), so the set goes to {@link LedgerPainter#paint} as it is.
     */
    @Nonnull
    public BookState withSection(@Nonnull String id, boolean open) {
        String clean = cleanId(id);
        if (clean == null) {
            return this;
        }
        return new BookState(tab, view, category, status, sort, search, tag, selectedId,
                LedgerPainter.withSection(openSections, clean, open));
    }

    /**
     * This state with new filters: a null argument keeps that filter, a blank one resets it to its default. The
     * selection, the view and the hand-toggled sections are kept.
     */
    @Nonnull
    public BookState withFilters(@Nullable String category, @Nullable String status, @Nullable String sort,
            @Nullable String search, @Nullable String tag) {
        return new BookState(tab, view,
                category != null ? category : this.category,
                status != null ? status : this.status,
                sort != null ? sort : this.sort,
                search != null ? search : this.search,
                tag != null ? tag : this.tag,
                selectedId, openSections);
    }

    /** {@link #withFilters} for the search alone. */
    @Nonnull
    public BookState withSearch(@Nullable String search) {
        return withFilters(null, null, null, search == null ? "" : search, null);
    }

    /** Every filter and the search back to their defaults; the sort, view, selection and sections kept. */
    @Nonnull
    public BookState clearFilters() {
        return withFilters(ALL, ALL, null, "", ALL);
    }

    /** On Achievements' overview with a row selected, the view that shows the row: Browse. */
    @Nonnull
    BookState showingSelection() {
        return selectedId != null && VIEW_OVERVIEW.equals(view) ? withView(VIEW_BROWSE) : this;
    }

    // ==================== reads ====================

    /** Whether any filter or the search narrows the list (an empty list then says "Nothing matches"). */
    public boolean anyFilter() {
        return !ALL.equalsIgnoreCase(category) || !ALL.equalsIgnoreCase(status) || !search.isEmpty()
                || !ALL.equalsIgnoreCase(tag);
    }

    /** Whether {@code section} shows its rows: the player's last word on it, else its own default. */
    public boolean isOpen(@Nonnull LedgerSection section) {
        return LedgerPainter.isOpen(section, openSections);
    }

    /** The view a fresh {@code tab} opens on. */
    @Nonnull
    public static String defaultView(@Nullable String tab) {
        return ObjectiveBookPage.TAB_ACHIEVEMENTS.equals(normalizeTab(tab)) ? VIEW_OVERVIEW : VIEW_BROWSE;
    }

    // ==================== helpers ====================

    @Nonnull
    private static String normalizeTab(@Nullable String tab) {
        return tab != null && ObjectiveBookPage.TAB_ACHIEVEMENTS.equalsIgnoreCase(tab.trim())
                ? ObjectiveBookPage.TAB_ACHIEVEMENTS : ObjectiveBookPage.TAB_QUESTS;
    }

    @Nonnull
    private static String orDefault(@Nullable String value, @Nonnull String fallback) {
        return blank(value) ? fallback : value.trim();
    }

    private static boolean blank(@Nullable String value) {
        return value == null || value.isBlank();
    }

    @Nonnull
    private static List<String> cleanIds(@Nonnull Collection<String> ids) {
        return ids.stream().map(BookState::cleanEntry).filter(Objects::nonNull).toList();
    }

    /** One {@link #openSections} entry trimmed, its {@link #CLOSED} mark kept; null when nothing is left. */
    @Nullable
    private static String cleanEntry(@Nullable String entry) {
        if (entry == null) {
            return null;
        }
        String trimmed = entry.trim();
        boolean closed = trimmed.startsWith(CLOSED);
        String id = cleanId(closed ? trimmed.substring(CLOSED.length()) : trimmed);
        return id == null ? null : (closed ? CLOSED + id : id);
    }

    /** A section id as it rides a binding: trimmed, never carrying the separator or the closed mark. */
    @Nullable
    private static String cleanId(@Nullable String id) {
        if (id == null) {
            return null;
        }
        String trimmed = id.trim().replace(SECTION_SEPARATOR, "");
        while (trimmed.startsWith(CLOSED)) {
            trimmed = trimmed.substring(CLOSED.length()).trim();
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Nonnull
    private static Set<String> splitSections(@Nullable String joined) {
        if (blank(joined)) {
            return Set.of();
        }
        return new LinkedHashSet<>(List.of(joined.split(SECTION_SEPARATOR)));
    }
}
