package com.ziggfreed.common.loot.reward;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/**
 * One reward as it READS: an optional item icon and one already-composed, client-resolved line.
 *
 * <p>Deliberately not a reward and deliberately not a {@link RewardSpec}. A chip is what a player
 * looks at before deciding whether something is worth taking, so it carries only what a row can
 * paint; what will actually be paid out stays the spec's business right up to the moment it is
 * granted.
 *
 * <p>The icon is nullable because a great many rewards have no item to show (a payout of experience,
 * a title, a console line). A chip with no icon renders as its line alone rather than borrowing some
 * unrelated item's picture, which would read as a promise of that item.
 *
 * <p>It lives beside the reward vocabulary rather than on any one screen, because every surface that
 * previews a payout - a quest detail panel, a storefront offer, a board contract, a results strip -
 * has to read one reward the same way, or the same reward reads differently depending on where a
 * player happens to be standing.
 */
public record RewardChip(@Nullable String iconItemId, @Nonnull Message label) {

    /** A chip showing an item's own picture beside its line. */
    @Nonnull
    public static RewardChip of(@Nullable String iconItemId, @Nonnull Message label) {
        return new RewardChip(iconItemId, label);
    }

    /** A chip that is a line and nothing else. */
    @Nonnull
    public static RewardChip text(@Nonnull Message label) {
        return new RewardChip(null, label);
    }

    /** Is there a picture to paint? */
    public boolean hasIcon() {
        return iconItemId != null && !iconItemId.isBlank();
    }
}
