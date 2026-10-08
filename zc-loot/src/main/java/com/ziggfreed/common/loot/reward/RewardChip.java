package com.ziggfreed.common.loot.reward;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.icon.IconSpec;

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
 * <p><b>A picture is either the item handed over or a picture that stands for the reward.</b> An item
 * reward's picture IS that item, so a row draws it with the item's own tooltip ({@link #showsItem()}).
 * A wallet or a reputation has no item of its own and borrows one for its picture (a wallet's
 * {@code Icon}, a reputation companion's), so a row draws that picture plain and hovering it says
 * {@link #tooltip()}, the reward's own name ({@link #picture}): the borrowed item's tooltip would name
 * the wrong thing.
 *
 * <p>{@link #icon()} is what a row paints, and it is the same {@link IconSpec} every other pictured
 * row in this library paints, so a payout and a step drawn side by side are drawn by one seam.
 *
 * <p>It lives beside the reward vocabulary rather than on any one screen, because every surface that
 * previews a payout - a quest detail panel, a storefront offer, a board contract, a results strip -
 * has to read one reward the same way, or the same reward reads differently depending on where a
 * player happens to be standing.
 *
 * @param tooltip what hovering the picture says when the picture only stands for the reward; null
 *                when the picture is the item handed over (or there is no picture)
 */
public record RewardChip(@Nullable String iconItemId, @Nonnull Message label, @Nullable Message tooltip) {

    /** A chip whose picture, when it has one, is the item it hands over. */
    public RewardChip(@Nullable String iconItemId, @Nonnull Message label) {
        this(iconItemId, label, null);
    }

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

    /**
     * A chip whose picture only STANDS FOR the reward (a wallet's icon, a reputation's): a row draws
     * it plain, and hovering it says {@code tooltip}, never the name of the item the picture borrows.
     * A null or blank icon reads as the line alone.
     */
    @Nonnull
    public static RewardChip picture(@Nullable String iconItemId, @Nonnull Message label,
            @Nonnull Message tooltip) {
        return new RewardChip(iconItemId, label, tooltip);
    }

    /** Is there a picture to paint? */
    public boolean hasIcon() {
        return iconItemId != null && !iconItemId.isBlank();
    }

    /**
     * Is the picture the item handed over, whose own tooltip is the point? False for a chip with no
     * picture and for a picture that only stands for the reward ({@link #picture}).
     */
    public boolean showsItem() {
        return hasIcon() && tooltip == null;
    }

    /** The picture to paint, in the form every pictured row in this library takes; null when none. */
    @Nullable
    public IconSpec icon() {
        return hasIcon() ? IconSpec.ofItem(iconItemId) : null;
    }
}
