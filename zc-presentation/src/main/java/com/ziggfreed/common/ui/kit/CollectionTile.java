package com.ziggfreed.common.ui.kit;

import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/**
 * A category tile: its id (the binding carries it), name, an optional count ("12 / 40"), picture, bar, the check
 * when complete, an optional pill ("On now"), an optional data accent strip ({@code #rrggbb}, clamped at paint
 * time), and {@code unseen} for the "new" mark.
 */
public record CollectionTile(@Nonnull String id, @Nonnull Message name, @Nullable Message count,
        @Nonnull Picture picture, @Nullable Progress progress, boolean complete, @Nullable Pill badge,
        @Nullable String accentHex, boolean unseen) {

    public CollectionTile {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        picture = picture == null ? Picture.NONE : picture;
    }
}
