package com.ziggfreed.common.ui.page;

import javax.annotation.Nonnull;

/**
 * One section of a page as its plan holds it, naming the part that draws it: {@link PagePainter} appends that
 * part's template and hands this plan to its painter.
 */
public interface PartPlan {

    @Nonnull
    PagePart<?, ?> part();
}
