package com.ziggfreed.common.objectives.book.achievement;

import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.objectives.book.BookState;

/**
 * What the Browse list is filtered to: a category ({@link #ALL} for every one), a status, a sort and the search
 * text. Every leaf is normalised on the way in, so an unknown status reads as {@link #ALL} and an unknown sort as
 * {@link #SORT_DEFAULT} (the shell's contract: a stale binding never empties the list).
 *
 * @param category a category id in lower case, or {@link #ALL}
 * @param status   {@link #ALL}, {@link #STATUS_PROGRESS}, {@link #STATUS_EARNED}, {@link #STATUS_WAITING} or
 *                 {@link #STATUS_FEATS}
 * @param sort     {@link #SORT_DEFAULT}, {@link #SORT_AZ} or {@link #SORT_CLOSEST}
 * @param search   the search text, trimmed; empty for none
 */
public record BrowseFilter(@Nonnull String category, @Nonnull String status, @Nonnull String sort,
        @Nonnull String search) {

    /** Every category, every status. */
    public static final String ALL = BookState.ALL;

    /** Not earned yet. */
    public static final String STATUS_PROGRESS = "progress";

    /** Earned, feats included. */
    public static final String STATUS_EARNED = "earned";

    /** Earned with rewards still to collect. */
    public static final String STATUS_WAITING = "waiting";

    /** The earned feats of strength only. */
    public static final String STATUS_FEATS = "feats";

    /** The statuses in the toolbar's order. */
    public static final List<String> STATUSES = List.of(ALL, STATUS_PROGRESS, STATUS_EARNED, STATUS_WAITING,
            STATUS_FEATS);

    /** In progress first, then the authored order, then the name. */
    public static final String SORT_DEFAULT = BookState.SORT_DEFAULT;

    /** By name. */
    public static final String SORT_AZ = "az";

    /** Closest to done first. */
    public static final String SORT_CLOSEST = "closest";

    /** The sorts in the dropdown's order. */
    public static final List<String> SORTS = List.of(SORT_DEFAULT, SORT_AZ, SORT_CLOSEST);

    /** Nothing filtered. */
    public static final BrowseFilter NONE = new BrowseFilter(ALL, ALL, SORT_DEFAULT, "");

    public BrowseFilter {
        category = blank(category) ? ALL : category.trim().toLowerCase(Locale.ROOT);
        status = known(status, STATUSES, ALL);
        sort = known(sort, SORTS, SORT_DEFAULT);
        search = search == null ? "" : search.trim();
    }

    /** The filter a book state carries. */
    @Nonnull
    public static BrowseFilter of(@Nonnull BookState state) {
        return new BrowseFilter(state.category(), state.status(), state.sort(), state.search());
    }

    /** Whether every category shows. */
    public boolean allCategories() {
        return ALL.equals(category);
    }

    /** Whether a search is typed (it opens every section and bypasses the ladder collapse). */
    public boolean searching() {
        return !search.isEmpty();
    }

    @Nonnull
    private static String known(@Nullable String value, @Nonnull List<String> vocabulary, @Nonnull String fallback) {
        if (blank(value)) {
            return fallback;
        }
        String folded = value.trim().toLowerCase(Locale.ROOT);
        return vocabulary.contains(folded) ? folded : fallback;
    }

    private static boolean blank(@Nullable String value) {
        return value == null || value.isBlank();
    }
}
