package com.ziggfreed.common.entity.performer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The prop a performer holds - a single resolved item id, or empty hands. A caller resolves its own
 * per-action/per-step prop POLICY (mirror the player's held item, a fixed authored tool, none) to
 * ONE of these; the backend owns only the MECHANISM of showing it ({@link HolderPerformer} mirrors
 * it onto the puppet's hotbar, {@link NpcRolePerformer} writes the NPC's native hotbar).
 *
 * @param itemId the item id to hold, or {@code null}/blank for empty hands.
 */
public record PropSpec(@Nullable String itemId) {

    /** Empty hands (clears any held prop). */
    @Nonnull
    public static PropSpec none() {
        return new PropSpec(null);
    }

    /** Hold {@code itemId} ({@code null}/blank is treated as {@link #none()}). */
    @Nonnull
    public static PropSpec of(@Nullable String itemId) {
        return new PropSpec(itemId);
    }

    /** Whether this spec clears the hand (null/blank item id). */
    public boolean isEmpty() {
        return itemId == null || itemId.isBlank();
    }
}
