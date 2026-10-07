package com.ziggfreed.common.ui.kit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Where a {@link LedgerPainter#paint} put everything, for the partial updates that follow it on the same page: each
 * row's button selector by row id, each section's selector, which sections are open and which have their rows
 * appended (a closed section appends its rows only when first opened). A page keeps the index its last full paint
 * returned; a partial update runs against that document, so a selector that is not here is never sent.
 */
public final class LedgerIndex {

    private final String listSelector;
    private final RowSize size;
    @Nullable
    private final PlayerRef viewer;
    private final Map<String, List<String>> rows = new HashMap<>();
    private final Map<String, LedgerRow> data = new HashMap<>();
    private final Map<String, Integer> sections = new HashMap<>();
    private final Set<String> open = new HashSet<>();
    private final Set<String> appended = new HashSet<>();
    @Nullable
    private String selected;

    LedgerIndex(@Nonnull String listSelector, @Nonnull RowSize size, @Nullable PlayerRef viewer) {
        this.listSelector = listSelector;
        this.size = size;
        this.viewer = viewer;
    }

    /** The row's button ({@code ... #Select}), the first copy when a row shows in two sections; null if not painted. */
    @Nullable
    public String rowSelector(@Nonnull String rowId) {
        List<String> copies = rows.get(rowId);
        return copies == null || copies.isEmpty() ? null : copies.get(0);
    }

    /** Every painted copy of the row (a pinned row shows in Pinned and in its own section). */
    @Nonnull
    public List<String> rowSelectors(@Nonnull String rowId) {
        List<String> copies = rows.get(rowId);
        return copies == null ? List.of() : List.copyOf(copies);
    }

    /** The section's root ({@code list[i] #Section}); null if not painted. */
    @Nullable
    public String sectionSelector(@Nonnull String sectionId) {
        Integer index = sections.get(sectionId);
        return index == null ? null : sectionRoot(index) + " #Section";
    }

    /** Whether the section shows its rows. */
    public boolean isOpen(@Nonnull String sectionId) {
        return open.contains(sectionId);
    }

    /** The row painted as selected, or null. */
    @Nullable
    public String selectedRowId() {
        return selected;
    }

    /** The list the sections were appended into. */
    @Nonnull
    public String listSelector() {
        return listSelector;
    }

    /** The row template the list paints. */
    @Nonnull
    public RowSize size() {
        return size;
    }

    @Nullable
    PlayerRef viewer() {
        return viewer;
    }

    void section(@Nonnull String sectionId, int index, boolean isOpen) {
        sections.put(sectionId, index);
        setOpen(sectionId, isOpen);
    }

    /** The section's position in the list, or -1 if not painted. */
    int sectionIndex(@Nonnull String sectionId) {
        Integer index = sections.get(sectionId);
        return index == null ? -1 : index;
    }

    /** The appended template's root, {@code list[index]}. */
    @Nonnull
    String sectionRoot(int index) {
        return KitPaint.child(listSelector, index);
    }

    void setOpen(@Nonnull String sectionId, boolean isOpen) {
        if (isOpen) {
            open.add(sectionId);
        } else {
            open.remove(sectionId);
        }
    }

    boolean rowsAppended(@Nonnull String sectionId) {
        return appended.contains(sectionId);
    }

    void markAppended(@Nonnull String sectionId) {
        appended.add(sectionId);
    }

    void row(@Nonnull LedgerRow row, @Nonnull String selector) {
        rows.computeIfAbsent(row.id(), id -> new ArrayList<>()).add(selector);
        data.put(row.id(), row);
    }

    /** What the row was painted with (which labels it shows, its tone), for a selection that restyles it. */
    @Nullable
    LedgerRow rowData(@Nonnull String rowId) {
        return data.get(rowId);
    }

    void select(@Nullable String rowId) {
        selected = rowId;
    }
}
