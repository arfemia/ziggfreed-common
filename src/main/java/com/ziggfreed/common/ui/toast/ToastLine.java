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
 */
public record ToastLine(@Nullable String iconItemId, int quantity, @Nonnull Message text) {

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

    public boolean hasIcon() {
        return iconItemId != null && !iconItemId.isEmpty();
    }
}
