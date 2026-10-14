package com.ziggfreed.common.ui.kit;

import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/**
 * One row of a ledger list, as a reader builds it: an id the page's binding carries, the title, an optional meta
 * line (the standard and tall rows only), the picture, the tone (the state pill, and the accent bar unless the row
 * says otherwise), the state word, a value in the trail (points, a tally; plain ink), a progress bar, the mark after
 * the title, {@code faint} for a finished row whose meta reads in the faint ink, the glyph the meta line opens with,
 * and what the accent bar shows.
 */
public record LedgerRow(@Nonnull String id, @Nonnull Message title, @Nullable Message meta, @Nonnull Picture picture,
        @Nonnull Tone tone, @Nullable Message state, @Nullable Message value, @Nullable Progress progress,
        @Nonnull Mark mark, boolean faint, @Nonnull MetaGlyph metaGlyph, @Nonnull RowAccent accent) {

    public LedgerRow {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        picture = picture == null ? Picture.NONE : picture;
        tone = tone == null ? Tone.NEUTRAL : tone;
        mark = mark == null ? Mark.NONE : mark;
        metaGlyph = metaGlyph == null ? MetaGlyph.NONE : metaGlyph;
        accent = accent == null ? RowAccent.TONE : accent;
    }

    /** A row with no meta glyph whose accent bar shows its tone. */
    public LedgerRow(@Nonnull String id, @Nonnull Message title, @Nullable Message meta, @Nonnull Picture picture,
            @Nonnull Tone tone, @Nullable Message state, @Nullable Message value, @Nullable Progress progress,
            @Nonnull Mark mark, boolean faint) {
        this(id, title, meta, picture, tone, state, value, progress, mark, faint, MetaGlyph.NONE, RowAccent.TONE);
    }
}
