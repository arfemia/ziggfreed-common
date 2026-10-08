package com.ziggfreed.common.ui.kit;

import javax.annotation.Nonnull;

/**
 * Which ledger row template a list paints: the standard two-line row, the compact one-line row, or the tall row
 * whose meta line wraps to two.
 *
 * <p>In every size a long title wraps onto a second line instead of ending in "...", a title longer still ends
 * its second line with "...", and the row grows to fit it: a template fixes no height, only its least one (a strut,
 * {@code #RowStrut}). So {@link #height()} is what a row takes with a one-line title, the least a list can count on
 * per row, never its exact height; size a list host to scroll, not to a count of rows.
 */
public enum RowSize {
    /** {@code Pages/ZigLedgerRow.ui}: title and meta, 56 high with a one-line title. */
    STANDARD("Pages/ZigLedgerRow.ui", ZigTokens.ROW_HEIGHT, true, false),
    /**
     * {@code Pages/ZigLedgerRowCompact.ui}: title and bar on one line, no meta, 44 high; a title on two lines grows it
     * only if the client draws the two lines taller than that.
     */
    COMPACT("Pages/ZigLedgerRowCompact.ui", ZigTokens.COMPACT_ROW_HEIGHT, false, false),
    /**
     * {@code Pages/ZigLedgerRowTall.ui}: title and a meta line that wraps to two, 74 high with a one-line title; for a
     * list whose meta runs long ("On now - 27 days left").
     */
    TALL("Pages/ZigLedgerRowTall.ui", ZigTokens.TALL_ROW_HEIGHT, true, true);

    private final String template;
    private final int height;
    private final boolean hasMeta;
    private final boolean metaWraps;

    RowSize(@Nonnull String template, int height, boolean hasMeta, boolean metaWraps) {
        this.template = template;
        this.height = height;
        this.hasMeta = hasMeta;
        this.metaWraps = metaWraps;
    }

    /** The template a painter appends, rooted at {@code Common/UI/Custom/}. */
    @Nonnull
    public String template() {
        return template;
    }

    /**
     * The row's height with a one-line title, its template's strut: the least it takes, since a title on two lines
     * grows it.
     */
    public int height() {
        return height;
    }

    /** Whether the template carries a {@code #Meta} line; a painter never addresses one that is not there. */
    public boolean hasMeta() {
        return hasMeta;
    }

    /**
     * Whether the template's {@code #Meta} wraps to a second line. The kit's named meta styles are one line, so a
     * painter recolours such a meta by its colour leaf alone and never swaps its whole style.
     */
    boolean metaWraps() {
        return metaWraps;
    }
}
