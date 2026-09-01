package com.ziggfreed.common.progress.runtime;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.icon.IconSpec;
import com.ziggfreed.common.progress.ObjectiveDef;

/**
 * How a consumer PICTURES one of its own steps, when it knows something about that step the generic
 * reading cannot recover.
 *
 * <p>The generic reading covers the ordinary cases on its own: a step naming one exact item is drawn
 * with that item's picture, one naming an exact creature with that creature's portrait, and one
 * naming a family of ids with whatever its kind's fallback row says. A source is for what is left -
 * a hand-in that carries no target at all but is delivered to a character with a face, a kind whose
 * target is an id in some registry only that mod knows how to picture.
 *
 * <p>Several may be registered and they are walked in registration order, FIRST NON-NULL WINS, so a
 * source that does not recognise a step answers null rather than inventing a picture, and the next
 * source still gets its turn. Answering null is also how a source declines a step it recognises but
 * has nothing to draw for: nothing is a valid answer, and better than a wrong picture.
 */
@FunctionalInterface
public interface ProgressionIconSource {

    /**
     * The picture for one step of {@code contentId}, or null when this source has none.
     *
     * @param contentId the quest or achievement the step belongs to
     * @param objective the step itself, carrying its kind, target and how that target matches
     */
    @Nullable
    IconSpec objectiveIcon(@Nonnull String contentId, @Nonnull ObjectiveDef objective);
}
