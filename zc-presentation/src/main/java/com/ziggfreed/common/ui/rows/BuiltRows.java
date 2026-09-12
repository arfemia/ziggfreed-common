package com.ziggfreed.common.ui.rows;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * What the last full build of a list actually drew: each row's INDEX, and the group it was drawn
 * under. A page records rows here as it appends them, then reads the record back when an action
 * changes something, to answer the two questions a scroll-preserving partial update depends on.
 *
 * <h2>Why a page cannot answer them itself</h2>
 *
 * <p>A {@code sendUpdate} runs against the DOM the last full build produced, so {@code #List[i]}
 * means row {@code i} OF THAT BUILD. Recomputing an index from a live, state-dependent list is
 * how a partial update lands on the wrong row: on a list that groups its rows, an index shifted
 * by one addresses a section HEADING, whose inner elements the row template does not have, and a
 * command written against a selector that resolves to nothing disconnects the player. So
 * {@link #indexOf} answers from the RECORD, and a row the record does not hold answers -1, which
 * every caller reads as "reopen instead".
 *
 * <p>The second question is whether a partial can still tell the truth at all. A list that groups
 * its rows - by section heading, or into a pinned block above a browse list - puts a row's group
 * on screen as position, and an action commonly MOVES a row between groups: a quest taken on
 * leaves "Available" for "Active", a collected one leaves the carried block entirely. Repainting
 * such a row in place leaves it sitting under a heading that now lies about it, with the group's
 * own counts stale beside it. {@link #moved} is that check: the group the row was BUILT under
 * against the group it belongs to now, so a page reopens exactly when a rebuild is the only
 * honest answer, and keeps its scroll every other time.
 *
 * <h2>Using it</h2>
 *
 * <pre>
 * rows.clear();                          // at the top of build
 * rows.addHeader();                      // a heading occupies a row index too
 * rows.add(quest.id(), section.name());  // the row under it
 * ...
 * if (rows.moved(quest.id(), liveSection.name())) { reopen(); } else { partialUpdate(); }
 * </pre>
 *
 * <p>Ids match case-insensitively and ignore surrounding space, the family's id rule, so a row
 * recorded under one spelling is still found when an event or a saved record hands back another.
 * A group is any string the caller picks - a section enum's name, a bucket word like
 * {@code "active"} - compared the same way; it is opaque here.
 *
 * <p>Per page instance and not thread-safe: it is written during build and read during that same
 * page's events, all on the world thread.
 */
public final class BuiltRows {

    /** The marker a heading row carries, so it holds an index without answering to any id. */
    private static final String HEADER = "";

    /** Row index to the id drawn there, headings included as {@link #HEADER}. */
    private final List<String> order = new ArrayList<>();

    /** Normalized id to the group its row was drawn under. */
    private final Map<String, String> groups = new HashMap<>();

    /** Forget the last build, which is the first thing a fresh build does. */
    public void clear() {
        order.clear();
        groups.clear();
    }

    /** Record a section heading: it answers to no id, but it does occupy a row index. */
    public void addHeader() {
        order.add(HEADER);
    }

    /** Record a row for {@code id}, drawn under {@code group} (null where a list has no groups). */
    public void add(@Nonnull String id, @Nullable String group) {
        order.add(id);
        groups.put(normalize(id), group == null ? "" : group.trim());
    }

    /** How many rows the last build drew, headings included. */
    public int size() {
        return order.size();
    }

    /** The index {@code id} was drawn at, or -1 when the last build drew no such row. */
    public int indexOf(@Nullable String id) {
        String needle = normalize(id);
        if (needle.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < order.size(); i++) {
            if (needle.equals(normalize(order.get(i)))) {
                return i;
            }
        }
        return -1;
    }

    /** The group {@code id}'s row was drawn under, or null when the last build drew no such row. */
    @Nullable
    public String groupOf(@Nullable String id) {
        String needle = normalize(id);
        return needle.isEmpty() ? null : groups.get(needle);
    }

    /**
     * Has the row for {@code id} stopped matching what is on screen? True when the last build drew
     * no such row, and true when it drew one under a different group than {@code liveGroup} - both
     * the cases where a partial update would leave the list lying, so the caller reopens.
     */
    public boolean moved(@Nullable String id, @Nullable String liveGroup) {
        String built = groupOf(id);
        if (built == null) {
            return true;
        }
        return !built.equalsIgnoreCase(liveGroup == null ? "" : liveGroup.trim());
    }

    @Nonnull
    private static String normalize(@Nullable String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
