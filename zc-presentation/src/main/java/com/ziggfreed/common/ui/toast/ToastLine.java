package com.ziggfreed.common.ui.toast;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/**
 * One body line of a toast: a client-resolved {@link Message} text plus an optional item
 * icon (rendered as a 24px slot beside the text). Transport-agnostic like the rest of the
 * {@code ui/toast} schema; a consumer maps its own reward/feedback rows into these so toast
 * rows and any companion panel rows stay in lock-step. The text is a {@link Message} so the
 * client resolves it in the player's own locale.
 *
 * <p>{@code quantity} is carried for a future slot-badge variant; the current renderer keeps
 * amounts in the text ("Moonbloom x2") and hides the badge.
 *
 * <p>Two orthogonal knobs shape a line inside a sectioned list ({@link RewardToastLines}):
 * {@code heading} paints it as a section header (bold, in the toast kind's colour) and
 * {@code indented} insets it under the header above it. A plain line carries neither.
 *
 * <p>A third, {@code pickupNoticed}, says the game's own pickup notice already told the player what
 * this line says (an item that landed as a pickup, {@code RewardSpec.pickupNoticed()}): a page still
 * paints it, and the corner feed, where that notice already sits, leaves it out
 * ({@link ToastDelivery}).
 */
public record ToastLine(@Nullable String iconItemId, int quantity, @Nonnull Message text,
                        boolean heading, boolean indented, boolean pickupNoticed) {

    /** A plain line: no heading, no indent. */
    public ToastLine(@Nullable String iconItemId, int quantity, @Nonnull Message text) {
        this(iconItemId, quantity, text, false, false, false);
    }

    /** A line with its heading and indent knobs, not pickup-noticed. */
    public ToastLine(@Nullable String iconItemId, int quantity, @Nonnull Message text,
                     boolean heading, boolean indented) {
        this(iconItemId, quantity, text, heading, indented, false);
    }

    /** An icon-less, text-only line (a pre-built {@link Message}). */
    @Nonnull
    public static ToastLine text(@Nonnull Message text) {
        return new ToastLine(null, 1, text);
    }

    /** An icon-less, text-only line wrapping a raw String as an untranslated {@link Message}. */
    @Nonnull
    public static ToastLine text(@Nonnull String text) {
        return new ToastLine(null, 1, Message.raw(text));
    }

    /** An icon line: the item icon + a pre-built {@link Message} text. */
    @Nonnull
    public static ToastLine item(@Nonnull String iconItemId, int quantity, @Nonnull Message text) {
        return new ToastLine(iconItemId, Math.max(1, quantity), text);
    }

    /** This line painted as a section header ({@code true}) or as a plain line ({@code false}). */
    @Nonnull
    public ToastLine withHeading(boolean heading) {
        return new ToastLine(iconItemId, quantity, text, heading, indented, pickupNoticed);
    }

    /** This line inset under the header above it ({@code true}) or flush ({@code false}). */
    @Nonnull
    public ToastLine withIndented(boolean indented) {
        return new ToastLine(iconItemId, quantity, text, heading, indented, pickupNoticed);
    }

    /** This line marked as already told by the game's own pickup notice ({@code true}) or not. */
    @Nonnull
    public ToastLine withPickupNoticed(boolean pickupNoticed) {
        return new ToastLine(iconItemId, quantity, text, heading, indented, pickupNoticed);
    }

    public boolean hasIcon() {
        return iconItemId != null && !iconItemId.isEmpty();
    }
}
