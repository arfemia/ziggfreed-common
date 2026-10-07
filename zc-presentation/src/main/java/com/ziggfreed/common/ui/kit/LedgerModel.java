package com.ziggfreed.common.ui.kit;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A whole ledger list: its sections in order, and the row a page selects when nothing is selected yet
 * ({@code firstSelectable}, null when the list has no row).
 */
public record LedgerModel(@Nonnull List<LedgerSection> sections, @Nullable String firstSelectable) {

    public LedgerModel {
        sections = sections == null ? List.of() : List.copyOf(sections);
    }

    /**
     * A model whose first selectable row is the first row of the first section that opens by default, else the
     * first row of any section.
     */
    @Nonnull
    public static LedgerModel of(@Nonnull List<LedgerSection> sections) {
        String first = null;
        for (LedgerSection section : sections) {
            if (section.openByDefault() && !section.rows().isEmpty()) {
                first = section.rows().get(0).id();
                break;
            }
        }
        if (first == null) {
            for (LedgerSection section : sections) {
                if (!section.rows().isEmpty()) {
                    first = section.rows().get(0).id();
                    break;
                }
            }
        }
        return new LedgerModel(sections, first);
    }

    /** True when no section holds a row. */
    public boolean isEmpty() {
        for (LedgerSection section : sections) {
            if (!section.rows().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** Whether any section holds a row with this id. */
    public boolean contains(@Nullable String rowId) {
        if (rowId == null) {
            return false;
        }
        for (LedgerSection section : sections) {
            for (LedgerRow row : section.rows()) {
                if (row.id().equals(rowId)) {
                    return true;
                }
            }
        }
        return false;
    }
}
