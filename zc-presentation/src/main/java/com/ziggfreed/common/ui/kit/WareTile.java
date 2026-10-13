package com.ziggfreed.common.ui.kit;

import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/**
 * One ware of a storefront grid: its id (the binding carries it), name and picture; what it costs, as a picture and an
 * amount ({@code cost} null shows no price line), the amount in the danger ink when the buyer cannot cover it
 * ({@code costShort}); an optional pill ("3 left", "Sold out"); {@code dimmed} for a ware out of reach (the scrim over
 * its picture) and {@code locked} for one behind a requirement (the lock glyph, and always dimmed); an optional
 * tooltip (its whole name, which can run past the tile's lines, and why it is out of reach); and {@code selected} for
 * the ware being read.
 */
public record WareTile(@Nonnull String id, @Nonnull Message name, @Nonnull Picture picture,
        @Nonnull Picture costPicture, @Nullable Message cost, boolean costShort, @Nullable Pill badge,
        boolean dimmed, boolean locked, @Nullable Message tooltip, boolean selected) {

    public WareTile {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        picture = picture == null ? Picture.NONE : picture;
        costPicture = costPicture == null ? Picture.NONE : costPicture;
        dimmed = dimmed || locked;
    }
}
