package com.ziggfreed.common.progress.runtime;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/**
 * How a surface with no catalogue of its own NAMES a piece of content.
 *
 * <p>The engines carry mechanics, not words: a {@code Quest} knows its steps and nothing about what
 * it is called. Whoever folded the content kept the text, so a shared surface walking the MERGED
 * catalogue would otherwise render half of it titled and the other half blank, depending on which
 * mod authored which entry.
 *
 * <p>Several may be registered and they are walked in registration order, FIRST NON-NULL WINS -
 * which is why every method may answer null. A source that does not recognise an id says so by
 * answering null rather than by inventing a placeholder, so the next source still gets its turn.
 */
public interface ProgressionTextSource {

    /** What this content is called, or null when this source does not know it. */
    @Nullable
    Message title(@Nonnull String contentId);

    /** The line under the title, or null. */
    @Nullable
    Message flavor(@Nonnull String contentId);

    /** What one step of this content is called, or null. */
    @Nullable
    Message objective(@Nonnull String contentId, @Nonnull String objectiveId);
}
