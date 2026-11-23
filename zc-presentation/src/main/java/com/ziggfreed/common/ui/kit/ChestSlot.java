package com.ziggfreed.common.ui.kit;

import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/**
 * A chest as a slot ({@code Pages/ZigItemSlotTile.ui} as a chest slot, {@link TilePainter#chestSlots}): its picture
 * on the game's panel patch, {@code taken} hanging the game's gold check under it once the player has taken it this
 * time round, and an optional tooltip. Its name, line and state word are the page's own words beside it.
 */
public record ChestSlot(@Nonnull String id, @Nonnull Picture picture, boolean taken, @Nullable Message tooltip) {

    public ChestSlot {
        Objects.requireNonNull(id, "id");
        picture = picture == null ? Picture.NONE : picture;
    }
}
