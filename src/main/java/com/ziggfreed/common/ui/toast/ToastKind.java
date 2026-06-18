package com.ziggfreed.common.ui.toast;

import javax.annotation.Nonnull;

import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;

/**
 * Severity of a toast plus its presentation tokens. Shared by every toast transport:
 * the in-page overlay ({@link ToastablePage}) today, and a future {@code CustomUIHud}-based
 * variant. {@link #feedStyle()} maps each kind to Hytale's native {@link NotificationStyle}
 * so the same kind can also drive the bottom-right notification feed.
 *
 * <p>The kind is conveyed by {@link #textColor()} (a bright 6-digit hex readable on the dark
 * panel): it colors BOTH the headline text AND the outer frame (the white frame texture is
 * tinted to this color via {@code Background.Color}), so gold = REWARD, green = SUCCESS,
 * red = ERROR at a glance. 6-digit on purpose: the runtime color parser ({@code ColorParseUtil})
 * only accepts {@code #rrggbb} / {@code #rrggbbaa} / {@code rgb()/rgba()} for {@code cmd.set(...)}
 * - the {@code #rrggbb(alpha)} parenthesized form is {@code .ui}-markup-only. Show/hide is driven
 * by the {@code Anchor} (see {@link ToastRenderer}).
 */
public enum ToastKind {
    ERROR  ("#ff7a7a", NotificationStyle.Danger),
    SUCCESS("#7affa0", NotificationStyle.Success),
    REWARD ("#ffd24a", NotificationStyle.Success),
    WARNING("#ffc24a", NotificationStyle.Warning),
    INFO   ("#7ab8ff", NotificationStyle.Default);

    private final String textColor;
    private final NotificationStyle feedStyle;

    ToastKind(@Nonnull String textColor, @Nonnull NotificationStyle feedStyle) {
        this.textColor = textColor;
        this.feedStyle = feedStyle;
    }

    /** Bright panel text color that signals this kind on the neutral bordered panel. */
    @Nonnull
    public String textColor() {
        return textColor;
    }

    /** Native notification-feed style for this kind (used by a future feed/HUD presenter). */
    @Nonnull
    public NotificationStyle feedStyle() {
        return feedStyle;
    }
}
