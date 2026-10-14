package com.ziggfreed.common.ui.kit;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The one glyph a list row's meta line may open with, saying what kind of thing the row is before its words do:
 * none, a gold star, a scroll, or a clock. Each is a kit glyph ({@code Common/Glyphs/}) in its own ink, so a reader
 * picks the meaning and never a colour (a quest list: a main quest, a side quest, one that comes round again); on
 * the selected row it turns white with the row's words. Only a row size with a meta line
 * ({@link RowSize#hasMeta()}) draws it.
 */
public enum MetaGlyph {
    NONE(null, null),
    STAR("#Star", ZigTokens.ACCENT),
    SCROLL("#Scroll", ZigTokens.INK_MUTED),
    CLOCK("#Clock", ZigTokens.INK_SECTION);

    @Nullable private final String child;
    @Nullable private final String ink;

    MetaGlyph(@Nullable String child, @Nullable String ink) {
        this.child = child;
        this.ink = ink;
    }

    /** Its element under the row's {@code #Kind} slot, or null for {@link #NONE}. */
    @Nullable
    String child() {
        return child;
    }

    /** The ink its template draws it in at rest, or null for {@link #NONE}. */
    @Nullable
    String ink() {
        return ink;
    }

    /** Every glyph a row can show, so a painter sets each one's visibility. */
    @Nonnull
    static MetaGlyph[] shown() {
        return new MetaGlyph[] {STAR, SCROLL, CLOCK};
    }
}
