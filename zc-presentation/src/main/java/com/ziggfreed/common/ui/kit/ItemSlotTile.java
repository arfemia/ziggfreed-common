package com.ziggfreed.common.ui.kit;

import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/**
 * One item of a collection grid: its picture; whether the player has had it ({@code owned}: the complete tile and its
 * check); whether it hides until found ({@code mystery}: the empty tile and its {@code glyph}, no picture); an
 * optional tooltip; and whether something the player does not meet yet keeps it from them ({@code locked}: the lock
 * glyph, the tooltip saying what it needs). A mystery is never owned, and neither is a locked one: owning an item
 * shows it for good.
 */
public record ItemSlotTile(@Nonnull String id, @Nonnull Picture picture, boolean owned, boolean mystery,
        @Nullable Message glyph, @Nullable Message tooltip, boolean locked) {

    public ItemSlotTile {
        Objects.requireNonNull(id, "id");
        picture = picture == null ? Picture.NONE : picture;
        mystery = mystery && !owned;
        locked = locked && !owned;
    }

    /** A slot nothing locks. */
    public ItemSlotTile(@Nonnull String id, @Nonnull Picture picture, boolean owned, boolean mystery,
            @Nullable Message glyph, @Nullable Message tooltip) {
        this(id, picture, owned, mystery, glyph, tooltip, false);
    }
}
