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

    private ToastSpec(@Nonnull ToastKind kind, @Nonnull Message message,
                      @Nullable Message title, @Nullable String iconPath, long durationMs,
                      @Nonnull List<ToastLine> lines) {
        this.kind = kind;
        this.message = message;
        this.title = title;
        this.iconPath = iconPath;
        this.durationMs = durationMs;
        this.lines = lines;
    }

    @Nonnull
    public static ToastSpec of(@Nonnull ToastKind kind, @Nonnull Message message) {
        return new ToastSpec(kind, message, null, null, 0L, List.of());
    }

    @Nonnull
    public ToastSpec withDuration(long ms) {
        return new ToastSpec(kind, message, title, iconPath, ms, lines);
    }

    @Nonnull
    public ToastSpec withTitle(@Nullable Message title) {
        return new ToastSpec(kind, message, title, iconPath, durationMs, lines);
    }

    @Nonnull
    public ToastSpec withIcon(@Nullable String iconPath) {
        return new ToastSpec(kind, message, title, iconPath, durationMs, lines);
    }

    @Nonnull
    public ToastSpec withLines(@Nonnull List<ToastLine> lines) {
        return new ToastSpec(kind, message, title, iconPath, durationMs, List.copyOf(lines));
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
}
