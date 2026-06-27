package com.ziggfreed.common.ui.toast;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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
 *
 * <p>{@link #soundId()} is the DEFAULT 3D SFX (a base-game {@code SoundEvent} id) the toast plays
 * when shown, so a UI-feedback toast makes a sensible noise by kind with zero per-call wiring
 * ({@link ToastablePage#showToast} plays it). A {@link ToastSpec#withSound override} replaces it and
 * {@link ToastSpec#silent()} suppresses it. Two kinds default to NO sound on purpose: {@code INFO}
 * (a passive informational toast should not chime) and {@code REWARD} (a reward toast is the VISUAL
 * companion to a grant whose authority - a quest / achievement / skill-tree claim or completion -
 * already plays the reward jingle; the toast adding a second chime would double it). A one-off
 * REWARD with no authority sound can opt in via {@link ToastSpec#withSound}.
 */
public enum ToastKind {
    ERROR  ("#ff7a7a", NotificationStyle.Danger,  "SFX_Creative_Play_Error"),
    SUCCESS("#7affa0", NotificationStyle.Success, "SFX_Workbench_Upgrade_Complete_Default"),
    REWARD ("#ffd24a", NotificationStyle.Success, null),
    WARNING("#ffc24a", NotificationStyle.Warning, "SFX_Generic_Crafting_Failed"),
    INFO   ("#7ab8ff", NotificationStyle.Default, null);

    private final String textColor;
    private final NotificationStyle feedStyle;
    @Nullable private final String soundId;

    ToastKind(@Nonnull String textColor, @Nonnull NotificationStyle feedStyle, @Nullable String soundId) {
        this.textColor = textColor;
        this.feedStyle = feedStyle;
        this.soundId = soundId;
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

    /** Default 3D SFX id played when a toast of this kind is shown; null = none (passive INFO). */
    @Nullable
    public String soundId() {
        return soundId;
    }
}
