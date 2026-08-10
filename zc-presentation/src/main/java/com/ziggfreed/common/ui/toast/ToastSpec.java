package com.ziggfreed.common.ui.toast;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/**
 * Immutable toast model. The shared "schema" consumed by every transport. v1 renders
 * {@link #kind()} + {@link #message()} + {@link #lines()}; {@link #title()},
 * {@link #iconPath()} and {@link #durationMs()} are carried so a richer renderer or the
 * future HUD/feed variant can use them without a schema change. {@code durationMs <= 0}
 * means "use the transport's default TTL".
 *
 * <p>{@link #message()} and {@link #title()} are client-resolved {@link Message} values (the
 * client renders them in its own locale). {@link #lines()} are {@link ToastLine} body rows
 * (icon + a {@link Message} text) rendered under the headline; empty means a plain
 * single-message toast.
 */
public final class ToastSpec {

    private final ToastKind kind;
    @Nonnull private final Message message;
    @Nullable private final Message title;
    @Nullable private final String iconPath;
    private final long durationMs;
    @Nonnull private final List<ToastLine> lines;
    @Nullable private final String soundOverride;
    private final boolean silent;

    private ToastSpec(@Nonnull ToastKind kind, @Nonnull Message message,
                      @Nullable Message title, @Nullable String iconPath, long durationMs,
                      @Nonnull List<ToastLine> lines, @Nullable String soundOverride, boolean silent) {
        this.kind = kind;
        this.message = message;
        this.title = title;
        this.iconPath = iconPath;
        this.durationMs = durationMs;
        this.lines = lines;
        this.soundOverride = soundOverride;
        this.silent = silent;
    }

    @Nonnull
    public static ToastSpec of(@Nonnull ToastKind kind, @Nonnull Message message) {
        return new ToastSpec(kind, message, null, null, 0L, List.of(), null, false);
    }

    @Nonnull
    public ToastSpec withDuration(long ms) {
        return new ToastSpec(kind, message, title, iconPath, ms, lines, soundOverride, silent);
    }

    @Nonnull
    public ToastSpec withTitle(@Nullable Message title) {
        return new ToastSpec(kind, message, title, iconPath, durationMs, lines, soundOverride, silent);
    }

    @Nonnull
    public ToastSpec withIcon(@Nullable String iconPath) {
        return new ToastSpec(kind, message, title, iconPath, durationMs, lines, soundOverride, silent);
    }

    @Nonnull
    public ToastSpec withLines(@Nonnull List<ToastLine> lines) {
        return new ToastSpec(kind, message, title, iconPath, durationMs, List.copyOf(lines), soundOverride, silent);
    }

    /** Replace the kind's default SFX with {@code soundId} (null falls back to the kind default). */
    @Nonnull
    public ToastSpec withSound(@Nullable String soundId) {
        return new ToastSpec(kind, message, title, iconPath, durationMs, lines, soundId, silent);
    }

    /** Suppress all SFX for this toast (its sound is owned elsewhere, or a chime is unwanted). */
    @Nonnull
    public ToastSpec silent() {
        return new ToastSpec(kind, message, title, iconPath, durationMs, lines, soundOverride, true);
    }

    @Nonnull
    public ToastKind kind() {
        return kind;
    }

    @Nonnull
    public Message message() {
        return message;
    }

    @Nullable
    public Message title() {
        return title;
    }

    @Nullable
    public String iconPath() {
        return iconPath;
    }

    public long durationMs() {
        return durationMs;
    }

    @Nonnull
    public List<ToastLine> lines() {
        return lines;
    }

    /** Whether this toast suppresses all SFX. */
    public boolean isSilent() {
        return silent;
    }

    /**
     * The 3D SFX id to play when this toast is shown, or null for none: the per-call
     * {@link #withSound override} if set, else this {@link #kind()}'s default; null when
     * {@link #silent()} or the kind has no default (INFO).
     */
    @Nullable
    public String effectiveSoundId() {
        if (silent) {
            return null;
        }
        return soundOverride != null ? soundOverride : kind.soundId();
    }
}
