package com.ziggfreed.common.ui.kit;

import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/**
 * A keepsake tile on a shelf: its label (the year), the state line under it, the picture, its state (earned, still
 * to earn, missed), {@code highlighted} for this year's tile, and an optional tooltip.
 */
public record KeepsakeTile(@Nonnull String id, @Nonnull Message label, @Nonnull Message stateLine,
        @Nonnull Picture picture, @Nonnull KeepsakeState state, boolean highlighted, @Nullable Message tooltip) {

    public KeepsakeTile {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(stateLine, "stateLine");
        picture = picture == null ? Picture.NONE : picture;
        state = state == null ? KeepsakeState.TO_EARN : state;
    }
}
