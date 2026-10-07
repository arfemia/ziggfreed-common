package com.ziggfreed.common.ui.kit;

import javax.annotation.Nonnull;

/** Which ledger row template a list paints: the standard two-line row or the compact one-line row. */
public enum RowSize {
    /** {@code Pages/ZigLedgerRow.ui}: title and meta, 56 high. */
    STANDARD("Pages/ZigLedgerRow.ui", ZigTokens.ROW_HEIGHT, true),
    /** {@code Pages/ZigLedgerRowCompact.ui}: title and bar on one line, no meta, 44 high. */
    COMPACT("Pages/ZigLedgerRowCompact.ui", ZigTokens.COMPACT_ROW_HEIGHT, false);

    private final String template;
    private final int height;
    private final boolean hasMeta;

    RowSize(@Nonnull String template, int height, boolean hasMeta) {
        this.template = template;
        this.height = height;
        this.hasMeta = hasMeta;
    }

    /** The template a painter appends, rooted at {@code Common/UI/Custom/}. */
    @Nonnull
    public String template() {
        return template;
    }

    /** The row's authored height. */
    public int height() {
        return height;
    }

    /** Whether the template carries a {@code #Meta} line; a painter never addresses one that is not there. */
    public boolean hasMeta() {
        return hasMeta;
    }
}
