package com.ziggfreed.common.objectives.trainer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.ui.page.PageSection;

/**
 * One kind of offer on the trainer page: what the part registered with it ({@link TrainerParts#register}) lists at
 * a trainer. The library's are Learn, Quests and Services; a mod adds its own kind (a station's recipes, its
 * techniques) by registering a part and its source, never by a change here. World thread, asked on every build;
 * one that throws costs only its own part.
 */
@FunctionalInterface
public interface TrainerSource {

    /**
     * What this kind offers at {@code at}; null, or no entries, leaves its part off the page.
     *
     * @param entry the trainer block's {@code Sections} entry that placed this part, whose group carries the part's
     *              own knobs ({@code entry.drawn(name, YourAsset.class)}); null when the block writes no
     *              {@code Sections} and the page shows every part
     */
    @Nullable
    TrainerOffers offers(@Nonnull TrainerAt at, @Nullable PageSection entry);
}
