package com.ziggfreed.common.ui.kit;

import java.util.List;
import java.util.Objects;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;

/**
 * A collapsible section of a ledger list: its head (label and row count), its rows, whether it opens by default,
 * and {@code cap}, how many rows show before a "Show N more" row (zero or less reads as {@link #DEFAULT_CAP}).
 */
public record LedgerSection(@Nonnull String id, @Nonnull Message label, @Nonnull List<LedgerRow> rows,
        boolean openByDefault, int cap) {

    /** Rows shown before "Show N more". */
    public static final int DEFAULT_CAP = 40;

    public LedgerSection {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(label, "label");
        rows = rows == null ? List.of() : List.copyOf(rows);
        cap = cap <= 0 ? DEFAULT_CAP : cap;
    }

    /** A section with the default cap. */
    public LedgerSection(@Nonnull String id, @Nonnull Message label, @Nonnull List<LedgerRow> rows,
            boolean openByDefault) {
        this(id, label, rows, openByDefault, DEFAULT_CAP);
    }
}
