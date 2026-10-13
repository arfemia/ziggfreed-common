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

    /** The rank of a progress notice, such as a quest step's ({@link #withPriority}). */
    public static final int PRIORITY_PROGRESS = 1;

    /** The rank of something earned: an achievement, a level. */
    public static final int PRIORITY_EARNED = 2;

    /** The rank of a payout or a finish: a claim, a quest completed, parked or collected. */
    public static final int PRIORITY_CLAIM = 3;

    private final ToastKind kind;
    @Nonnull private final Message message;
    @Nullable private final String iconItemId;
    private final long durationMs;
    @Nonnull private final List<ToastLine> lines;
    @Nullable private final String soundOverride;
    private final boolean silent;
    private final int priority;

    private ToastSpec(@Nonnull ToastKind kind, @Nonnull Message message,
                      @Nullable String iconItemId, long durationMs,
                      @Nonnull List<ToastLine> lines, @Nullable String soundOverride, boolean silent,
                      int priority) {
        this.kind = kind;
        this.message = message;
        this.iconItemId = iconItemId;
        this.durationMs = durationMs;
        this.lines = lines;
        this.soundOverride = soundOverride;
        this.silent = silent;
        this.priority = priority;
    }

    @Nonnull
    public static ToastSpec of(@Nonnull ToastKind kind, @Nonnull Message message) {
        return new ToastSpec(kind, message, null, 0L, List.of(), null, false, 0);
    }

    @Nonnull
    public ToastSpec withDuration(long ms) {
        return new ToastSpec(kind, message, iconItemId, ms, lines, soundOverride, silent, priority);
    }

    @Nonnull
    public ToastSpec withIcon(@Nullable String iconItemId) {
        return new ToastSpec(kind, message, iconItemId, durationMs, lines, soundOverride, silent, priority);
    }

    @Nonnull
    public ToastSpec withLines(@Nonnull List<ToastLine> lines) {
        return new ToastSpec(kind, message, iconItemId, durationMs, List.copyOf(lines), soundOverride, silent,
                priority);
    }

    /** Replace the kind's default SFX with {@code soundId} (null falls back to the kind default). */
    @Nonnull
    public ToastSpec withSound(@Nullable String soundId) {
        return new ToastSpec(kind, message, iconItemId, durationMs, lines, soundId, silent, priority);
    }

    /** Suppress all SFX for this toast (its sound is owned elsewhere, or a chime is unwanted). */
    @Nonnull
    public ToastSpec silent() {
        return new ToastSpec(kind, message, iconItemId, durationMs, lines, soundOverride, true, priority);
    }

    /**
     * Rank this toast against the others raised in the same moment ({@link ToastStore#show}): a ranked
     * toast ({@code priority > 0}) never replaces a higher-ranked one still on screen, so a payout's
     * notice is not overwritten by a progress tick fired a moment later; it waits its turn behind it
     * instead. 0, the default, is unranked: it always shows, as a page's own answer to a click does, and
     * holds nothing back. The shipped ranks are {@link #PRIORITY_PROGRESS}, {@link #PRIORITY_EARNED} and
     * {@link #PRIORITY_CLAIM}.
     */
    @Nonnull
    public ToastSpec withPriority(int priority) {
        return new ToastSpec(kind, message, iconItemId, durationMs, lines, soundOverride, silent,
                Math.max(0, priority));
    }

    /** This toast's rank; 0 is unranked ({@link #withPriority}). */
    public int priority() {
        return priority;
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
