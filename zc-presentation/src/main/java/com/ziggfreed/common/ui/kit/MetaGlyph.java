package com.ziggfreed.common.ui.kit;

/**
 * The one glyph a list row's meta line may open with, saying what kind of thing the row is before its words do:
 * none, a gold star, a scroll, or a clock. Each is a kit glyph ({@code Common/Glyphs/}) in its own fixed ink, so a
 * reader picks the meaning and never a colour (a quest list: a main quest, a side quest, one that comes round
 * again). Only a row size with a meta line ({@link RowSize#hasMeta()}) draws it.
 */
public enum MetaGlyph {
    NONE,
    STAR,
    SCROLL,
    CLOCK
}
