package com.ziggfreed.common.ui.toast;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/**
 * Immutable toast model. The shared "schema" consumed by every transport: a panel is
 * {@link #kind()} + one headline {@link #message()} + a stack of {@link #lines()} body rows.
 * {@link #iconItemId()} illustrates the headline (an ITEM id, drawn as a slot beside the words the
 * way a reward row's own icon is) and {@link #durationMs()} is carried for a transport with its own
 * TTL; {@code durationMs <= 0} means "use the transport's default".
 *
 * <p><b>There is ONE headline.</b> A producer with two lines to say puts the sentence naming what
 * happened in {@link #message()} and the rest in a {@link ToastLine} row, because a field the
 * renderer does not paint is a line the player never reads while every call site looks correct.
 *
 * <p>{@link #message()} is a client-resolved {@link Message} value (the client renders it in its
 * own locale). {@link #lines()} are {@link ToastLine} body rows (icon + a {@link Message} text)
 * rendered under the headline; empty means a plain single-message toast.
 */
public final class ToastSpec {

    private final ToastKind kind;
    @Nonnull private final Message message;
    @Nullable private final String iconItemId;
    private final long durationMs;
    @Nonnull private final List<ToastLine> lines;
    @Nullable private final String soundOverride;
    private final boolean silent;

    private ToastSpec(@Nonnull ToastKind kind, @Nonnull Message message,
                      @Nullable String iconItemId, long durationMs,
                      @Nonnull List<ToastLine> lines, @Nullable String soundOverride, boolean silent) {
        this.kind = kind;
        this.message = message;
        this.iconItemId = iconItemId;
        this.durationMs = durationMs;
        this.lines = lines;
        this.soundOverride = soundOverride;
        this.silent = silent;
    }

    @Nonnull
    public static ToastSpec of(@Nonnull ToastKind kind, @Nonnull Message message) {
        return new ToastSpec(kind, message, null, 0L, List.of(), null, false);
    }

    @Nonnull
    public ToastSpec withDuration(long ms) {
        return new ToastSpec(kind, message, iconItemId, ms, lines, soundOverride, silent);
    }

    @Nonnull
    public ToastSpec withIcon(@Nullable String iconItemId) {
        return new ToastSpec(kind, message, iconItemId, durationMs, lines, soundOverride, silent);
    }

    @Nonnull
    public ToastSpec withLines(@Nonnull List<ToastLine> lines) {
        return new ToastSpec(kind, message, iconItemId, durationMs, List.copyOf(lines), soundOverride, silent);
    }

    /** Replace the kind's default SFX with {@code soundId} (null falls back to the kind default). */
    @Nonnull
    public ToastSpec withSound(@Nullable String soundId) {
        return new ToastSpec(kind, message, iconItemId, durationMs, lines, soundId, silent);
    }

    /** Suppress all SFX for this toast (its sound is owned elsewhere, or a chime is unwanted). */
    @Nonnull
    public ToastSpec silent() {
        return new ToastSpec(kind, message, iconItemId, durationMs, lines, soundOverride, true);
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
    public String iconItemId() {
        return iconItemId;
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
