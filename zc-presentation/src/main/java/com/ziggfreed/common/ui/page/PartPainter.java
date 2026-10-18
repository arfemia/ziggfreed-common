package com.ziggfreed.common.ui.page;

import javax.annotation.Nonnull;

/**
 * Fills one part's template, already appended at {@link PartCanvas#root()}, from the part's plan. It addresses
 * only ids its own template declares, under the canvas root ({@link PartCanvas#at}): a {@code .set} on an id
 * the template lacks disconnects the player.
 *
 * @param <P> the plan it paints
 */
@FunctionalInterface
public interface PartPainter<P> {

    void paint(@Nonnull PartCanvas canvas, @Nonnull P plan);
}
