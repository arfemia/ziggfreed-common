package com.ziggfreed.common.ui.kit;

import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/**
 * The band frame's hero, as a page fills it ({@link HeroPainter}): an optional emblem over the title (a texture path
 * rooted at {@code Common/}, the root an {@code AssetImage} reads, such as
 * {@code UI/Custom/Pages/Memories/categories/VoidspawnComplete.png}; null or blank for none), the headline under the
 * game's gold text gradient, an optional copper tagline under it, and an optional line under the game's rule.
 */
public record Hero(@Nullable String emblem, @Nonnull Message headline, @Nullable Message tagline,
        @Nullable Message sub) {

    public Hero {
        Objects.requireNonNull(headline, "headline");
        emblem = emblem == null || emblem.isBlank() ? null : emblem.trim();
    }

    /** A hero that is its headline alone. */
    @Nonnull
    public static Hero of(@Nonnull Message headline) {
        return new Hero(null, headline, null, null);
    }
}
