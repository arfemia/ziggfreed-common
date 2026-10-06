package com.ziggfreed.common.ui.kit;

import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/**
 * A statistic tile: picture, the figure (a typed number), its name, an optional caption ("12 in all"), an optional
 * server line, and {@code zero} for a figure of nothing, which reads in the faint ink.
 */
public record StatTile(@Nonnull String id, @Nonnull Picture picture, @Nonnull Message figure, @Nonnull Message name,
        @Nullable Message caption, @Nullable Message serverLine, boolean zero) {

    public StatTile {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(figure, "figure");
        Objects.requireNonNull(name, "name");
        picture = picture == null ? Picture.NONE : picture;
    }
}
