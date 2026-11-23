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
 * what the accent bar shows, and the row's one {@code action} (a notice's Ask to join), a button after its trail.
 *
 * <p>The action is a {@link DetailAction}, the kit's one button vocabulary: its label, its look (its slot's own, a
 * notice's Secondary, unless its look says otherwise), whether it can be pressed now (greyed in place when not) and an
 * optional tooltip. The page binds it through {@link LedgerBindings#action}; the row's own click stays the row's.
 */
public record LedgerRow(@Nonnull String id, @Nonnull Message title, @Nullable Message meta, @Nonnull Picture picture,
        @Nonnull Tone tone, @Nullable Message state, @Nullable Message value, @Nullable Progress progress,
        @Nonnull Mark mark, boolean faint, @Nonnull MetaGlyph metaGlyph, @Nonnull RowAccent accent,
        @Nullable DetailAction action) {

    public LedgerRow {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        picture = picture == null ? Picture.NONE : picture;
        tone = tone == null ? Tone.NEUTRAL : tone;
        mark = mark == null ? Mark.NONE : mark;
        metaGlyph = metaGlyph == null ? MetaGlyph.NONE : metaGlyph;
        accent = accent == null ? RowAccent.TONE : accent;
    }

    /** A row with no action of its own, the common case. */
    public LedgerRow(@Nonnull String id, @Nonnull Message title, @Nullable Message meta, @Nonnull Picture picture,
            @Nonnull Tone tone, @Nullable Message state, @Nullable Message value, @Nullable Progress progress,
            @Nonnull Mark mark, boolean faint, @Nonnull MetaGlyph metaGlyph, @Nonnull RowAccent accent) {
        this(id, title, meta, picture, tone, state, value, progress, mark, faint, metaGlyph, accent, null);
    }

    /** A row with no meta glyph whose accent bar shows its tone. */
    public LedgerRow(@Nonnull String id, @Nonnull Message title, @Nullable Message meta, @Nonnull Picture picture,
            @Nonnull Tone tone, @Nullable Message state, @Nullable Message value, @Nullable Progress progress,
            @Nonnull Mark mark, boolean faint) {
        this(id, title, meta, picture, tone, state, value, progress, mark, faint, MetaGlyph.NONE, RowAccent.TONE);
    }

    /** This row with {@code action} as its one button (null for none). */
    @Nonnull
    public LedgerRow withAction(@Nullable DetailAction action) {
        return new LedgerRow(id, title, meta, picture, tone, state, value, progress, mark, faint, metaGlyph, accent,
                action);
    }
}
