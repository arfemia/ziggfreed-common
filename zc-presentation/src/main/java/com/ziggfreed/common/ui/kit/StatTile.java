package com.ziggfreed.common.ui.kit;

import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/**
 * A statistic tile: picture ({@link Picture#NONE} gives its slot back to the figure), the figure (a typed number), its
 * name, an optional caption ("12 in all"), an optional server line, {@code zero} for a figure of nothing, which reads
 * in the faint ink, and an optional {@code tone} for the figure, read as a header {@link Stat}'s is (gold for
 * {@link Tone#COLLECT}, a first place; null keeps the figure's own ink). A zero figure stays faint whatever its tone.
 */
public record StatTile(@Nonnull String id, @Nonnull Picture picture, @Nonnull Message figure, @Nonnull Message name,
        @Nullable Message caption, @Nullable Message serverLine, boolean zero, @Nullable Tone tone) {

    public StatTile {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(figure, "figure");
        Objects.requireNonNull(name, "name");
        picture = picture == null ? Picture.NONE : picture;
    }

    /** A tile whose figure keeps its own ink. */
    public StatTile(@Nonnull String id, @Nonnull Picture picture, @Nonnull Message figure, @Nonnull Message name,
            @Nullable Message caption, @Nullable Message serverLine, boolean zero) {
        this(id, picture, figure, name, caption, serverLine, zero, null);
    }

    /** This tile with its figure in {@code tone} (null for the figure's own ink). */
    @Nonnull
    public StatTile withTone(@Nullable Tone tone) {
        return new StatTile(id, picture, figure, name, caption, serverLine, zero, tone);
    }
}
