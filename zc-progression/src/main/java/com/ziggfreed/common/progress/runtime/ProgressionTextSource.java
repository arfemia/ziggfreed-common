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

    /**
     * The narrative this content reads with while it is in {@code state} - the paragraph a giver's
     * screen shows above the steps, which changes as the quest moves along.
     *
     * <p>It is a SEAM rather than a schema leaf because the words already exist: a consumer resolves
     * it from whatever convention its own content uses ({@code quest.<id>.md.<state>} is the one the
     * library's own folded content follows), and content authored before this existed keeps working
     * untouched.
     *
     * <p>{@code state} is the lower-case lifecycle word a surface is showing: {@code incomplete}
     * before it is taken, {@code active} while it is carried, {@code complete} once it is finished. A
     * source that has nothing for a state answers null and the surface falls back to
     * {@link #flavor}, which is what the great majority of content has and all of it may have.
     *
     * <p>DEFAULT-BODIED: a source written before this existed answers null for every state, which is
     * the honest answer for one that carries no narrative at all.
     */
    @Nullable
    default Message lore(@Nonnull String contentId, @Nonnull String state) {
        return null;
    }
}
