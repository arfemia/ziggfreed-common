package com.ziggfreed.common.ui.menu;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/** A consumer's block of tabs: a heading (none for no heading row) over its entries, in order. */
public record MenuSection(@Nullable Message header, @Nonnull List<MenuEntry> entries) {

    public MenuSection {
        entries = List.copyOf(entries);
    }
}
