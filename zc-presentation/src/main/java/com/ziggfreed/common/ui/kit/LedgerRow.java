package com.ziggfreed.common.ui.kit;

import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/**
 * One row of a ledger list, as a reader builds it: an id the page's binding carries, the title, an optional meta
 * line (the standard row only), the picture, the tone (accent bar and state word), the state word, a value in the
 * trail (points, a tally; plain ink), a progress bar, the mark after the title, and {@code faint} for a finished
 * row whose meta reads in the faint ink.
 */
public record LedgerRow(@Nonnull String id, @Nonnull Message title, @Nullable Message meta, @Nonnull Picture picture,
        @Nonnull Tone tone, @Nullable Message state, @Nullable Message value, @Nullable Progress progress,
        @Nonnull Mark mark, boolean faint) {

    public LedgerRow {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        picture = picture == null ? Picture.NONE : picture;
        tone = tone == null ? Tone.NEUTRAL : tone;
        mark = mark == null ? Mark.NONE : mark;
    }
}
