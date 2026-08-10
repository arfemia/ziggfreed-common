package com.ziggfreed.common.instance.result;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/**
 * One reward shown in the results-screen reward strip: an optional item-icon id + a
 * client-resolved label (which carries the amount, e.g. "x5 Emerald") + whether it was
 * granted or is still PENDING (blocked by a full inventory). Mirrors hyMMO's
 * RewardPreview itemId-XOR-text shape as a pure record; no MMO reward engine lifted.
 */
public record RewardChip(@Nullable String iconItemId, @Nonnull Message label, boolean pending) {

    /** A granted reward chip with an item icon. */
    @Nonnull
    public static RewardChip item(@Nonnull String iconItemId, @Nonnull Message label) {
        return new RewardChip(iconItemId, label, false);
    }

    /** A granted reward chip with no icon (currency / command). */
    @Nonnull
    public static RewardChip text(@Nonnull Message label) {
        return new RewardChip(null, label, false);
    }

    /** Mark this chip pending (blocked, awaiting space / re-claim). */
    @Nonnull
    public RewardChip asPending() {
        return new RewardChip(iconItemId, label, true);
    }
}
