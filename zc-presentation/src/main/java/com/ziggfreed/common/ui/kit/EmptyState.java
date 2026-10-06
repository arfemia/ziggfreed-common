package com.ziggfreed.common.ui.kit;

import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/**
 * What a blank list, page or picker shows ({@code @ZigEmptyState}): a picture, a title, an optional line, and an
 * optional button (only its label is painted; the page binds it).
 */
public record EmptyState(@Nonnull Picture picture, @Nonnull Message title, @Nullable Message line,
        @Nullable DetailAction action) {

    public EmptyState {
        Objects.requireNonNull(title, "title");
        picture = picture == null ? Picture.NONE : picture;
    }
}
