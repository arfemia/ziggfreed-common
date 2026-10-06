package com.ziggfreed.common.ui.kit;

import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/**
 * A short filled label ("On now", "Server first", "Waiting"): the word in its tone's colour on the scrim with the
 * tone's dot, or, with {@code fillHex} (a data accent such as a season's), on that fill in bright ink.
 */
public record Pill(@Nonnull Message label, @Nonnull Tone tone, @Nullable String fillHex) {

    public Pill {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(tone, "tone");
    }

    /** A tone pill on the scrim. */
    @Nonnull
    public static Pill of(@Nonnull Message label, @Nonnull Tone tone) {
        return new Pill(label, tone, null);
    }
}
