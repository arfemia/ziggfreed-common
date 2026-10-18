package com.ziggfreed.common.ui.page;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One entry of a composed page's {@code Sections}: ONE part of the page's body, named by the group the entry
 * writes ({@code { "Banner": { ... } }}). There is no {@code Type} to pick: the group IS the part, and the
 * page's registered parts ({@link PageParts}) say which groups are parts.
 *
 * <p>An entry naming two parts draws the first in registration order; an entry naming none is skipped. A group
 * no part of the page is named for is kept by name ({@link #unknownParts}) for the page's audit to report, once,
 * as a warning, and never draws. Only the drawn part's group is handed out ({@link #drawn}), so a second part
 * written beside it can never draw. A page with typed accessors for its own parts extends this class and reads
 * its sections through {@link PageParts#sectionCodec}.
 */
public class PageSection {

    private List<String> written = List.of();
    private Map<String, Object> values = Map.of();
    private List<String> unknown = List.of();

    public PageSection() {
    }

    /** What the decode found: the parts written, in registration order, their groups, and the other names. */
    final void fill(@Nonnull List<String> partsInOrder, @Nonnull Map<String, Object> groups,
            @Nonnull List<String> otherNames) {
        written = List.copyOf(partsInOrder);
        values = new HashMap<>(groups);
        unknown = List.copyOf(otherNames);
    }

    /** Every part this entry writes, in registration order; empty for an entry naming none. */
    @Nonnull
    public List<String> partsWritten() {
        return written;
    }

    /** The part this entry draws: the first it writes, in registration order; null when it writes none. */
    @Nullable
    public String part() {
        return written.isEmpty() ? null : written.get(0);
    }

    /**
     * The group of {@code name} when it is this entry's drawn part and reads as a {@code type}; else null. A part
     * written beside the drawn one is never handed out.
     */
    @Nullable
    public <A> A drawn(@Nonnull String name, @Nonnull Class<A> type) {
        if (!name.equals(part())) {
            return null;
        }
        Object value = values.get(name);
        return type.isInstance(value) ? type.cast(value) : null;
    }

    /** The groups this entry names that are no part of its page, in the order written. */
    @Nonnull
    public List<String> unknownParts() {
        return unknown;
    }

    /** {@code name}'s group as decoded, drawn or not, for the encode. */
    @Nullable
    final Object group(@Nonnull String name) {
        return values.get(name);
    }
}
