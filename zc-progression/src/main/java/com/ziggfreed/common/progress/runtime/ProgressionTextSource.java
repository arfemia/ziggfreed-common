package com.ziggfreed.common.progress.runtime;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.i18n.ContentKeys;

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
     * <p>DEFAULT-BODIED, and the default IS the convention: it answers whichever
     * {@code quest.<id>.md.<state>} key a registered catalogue actually ships, and null when none
     * does. That is what makes the convention one rule rather than one per source - a mod that
     * writes the keys gets its narrative rendered without registering anything, and a source with
     * something better to say still overrides this.
     */
    @Nullable
    default Message lore(@Nonnull String contentId, @Nonnull String state) {
        return loreByConvention(contentId, state);
    }

    /**
     * The shared narrative convention, as a plain lookup: {@code quest.<id>.md.<state>}, answered
     * only when some registered catalogue really ships that key so a missing one falls through to
     * whatever the surface shows instead of painting a raw key at the player.
     */
    @Nullable
    static Message loreByConvention(@Nonnull String contentId, @Nonnull String state) {
        String key = "quest." + contentId + ".md." + state;
        try {
            return ContentKeys.known(key) ? ContentKeys.tr(key) : null;
        } catch (Throwable notLoaded) {
            return null;
        }
    }
}
