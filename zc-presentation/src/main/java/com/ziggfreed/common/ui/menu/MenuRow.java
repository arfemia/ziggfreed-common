package com.ziggfreed.common.ui.menu;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/**
 * One row the rail drew, in its {@code #MenuList} index order: a heading, a tab, or the gap between sections.
 * A tab carries the second line its entry gave for this paint ({@link MenuEntry#subline()}), or none.
 */
public record MenuRow(@Nonnull Kind kind, @Nullable Message header, @Nullable MenuEntry entry,
                      @Nullable MenuSubline subline) {

    public enum Kind { HEADER, ENTRY, SPACER }

    /** The gap between the consumer's section and the library's slots. */
    public static final MenuRow SPACER = new MenuRow(Kind.SPACER, null, null);

    /** A row with no second line. */
    public MenuRow(@Nonnull Kind kind, @Nullable Message header, @Nullable MenuEntry entry) {
        this(kind, header, entry, null);
    }

    @Nonnull
    public static MenuRow header(@Nonnull Message header) {
        return new MenuRow(Kind.HEADER, header, null);
    }

    @Nonnull
    public static MenuRow entry(@Nonnull MenuEntry entry) {
        return new MenuRow(Kind.ENTRY, null, entry);
    }

    /** A tab, with the second line it gave for this paint (null for none). */
    @Nonnull
    public static MenuRow entry(@Nonnull MenuEntry entry, @Nullable MenuSubline subline) {
        return new MenuRow(Kind.ENTRY, null, entry, subline);
    }
}
