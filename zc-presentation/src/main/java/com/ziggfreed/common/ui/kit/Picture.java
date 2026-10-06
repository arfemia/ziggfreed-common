package com.ziggfreed.common.ui.kit;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * What a picture slot draws: an item, whose own icon texture is drawn ({@code ItemIds.iconPath}), or a texture path
 * rooted at {@code Common/} ({@code "UI/Custom/..."}, {@code "Icons/..."}). An item the server does not ship falls
 * back to the texture, and with neither the picture hides. Every kit slot is an {@code AssetImage #IcoTex} painted
 * through {@code IconRenderer.applyPlainIcon}, so a picture only displays: no tooltip, no rarity square.
 *
 * <p>{@link #tooltip()} asks for the item's own tooltip instead, and only a detail line honours it (a reward line,
 * whose item tooltip is the point): there the item goes into the line's styled one-slot {@code ItemGrid #IcoItem}.
 * Every other slot draws it plain. Blank leaves read as absent.
 */
public record Picture(@Nullable String itemId, @Nullable String texturePath, boolean tooltip) {

    /** Nothing to draw: the slot hides. */
    public static final Picture NONE = new Picture(null, null);

    public Picture {
        itemId = itemId == null || itemId.isBlank() ? null : itemId;
        texturePath = texturePath == null || texturePath.isBlank() ? null : texturePath;
    }

    /** A plain picture from its two leaves. */
    public Picture(@Nullable String itemId, @Nullable String texturePath) {
        this(itemId, texturePath, false);
    }

    /** An item's own icon. */
    @Nonnull
    public static Picture item(@Nullable String itemId) {
        return new Picture(itemId, null);
    }

    /** A texture, rooted at {@code Common/}. */
    @Nonnull
    public static Picture texture(@Nullable String path) {
        return new Picture(null, path);
    }

    /** An item shown with its own tooltip and rarity square, on a detail line (a reward). */
    @Nonnull
    public static Picture tooltipItem(@Nullable String itemId) {
        return new Picture(itemId, null, true);
    }

    /**
     * This picture, falling back to {@code fallback}: an empty picture is the fallback; an item keeps its own texture,
     * else takes the fallback's, so an item the server does not ship still draws something; a texture alone is
     * itself.
     */
    @Nonnull
    public Picture or(@Nonnull Picture fallback) {
        if (isEmpty()) {
            return fallback;
        }
        if (itemId != null && texturePath == null) {
            return new Picture(itemId, fallback.texturePath(), tooltip);
        }
        return this;
    }

    /** True when there is nothing to draw. */
    public boolean isEmpty() {
        return itemId == null && texturePath == null;
    }
}
