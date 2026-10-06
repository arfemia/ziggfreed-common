package com.ziggfreed.common.ui.kit;

import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/**
 * One line of a detail block: a picture, the text, an optional count at the end ("3 / 5"), an optional pill
 * ("Waiting"), the tick glyph at the start, {@code selectId} when the line opens something (the page's binding
 * carries it), and {@code current} for the line that is the reader's own place (a ladder's current tier).
 */
public record DetailLine(@Nonnull Picture picture, @Nonnull Message text, @Nullable Message count, @Nullable Pill tag,
        @Nonnull Tick tick, @Nullable String selectId, boolean current) {

    public DetailLine {
        Objects.requireNonNull(text, "text");
        picture = picture == null ? Picture.NONE : picture;
        tick = tick == null ? Tick.NONE : tick;
        selectId = selectId == null || selectId.isBlank() ? null : selectId;
    }

    /** A plain line: a picture and its text. */
    @Nonnull
    public static DetailLine of(@Nonnull Picture picture, @Nonnull Message text) {
        return new DetailLine(picture, text, null, null, Tick.NONE, null, false);
    }
}
