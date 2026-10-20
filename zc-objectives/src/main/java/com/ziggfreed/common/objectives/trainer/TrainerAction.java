package com.ziggfreed.common.objectives.trainer;

import javax.annotation.Nonnull;

/**
 * What an entry's main button does: run on the player's press, against the live state (never the last render's), and
 * say what to tell them. World thread.
 */
@FunctionalInterface
public interface TrainerAction {

    /** Do it for the player at {@code at}, and answer what the page says and whether another screen took its place. */
    @Nonnull
    TrainerResult press(@Nonnull TrainerAt at);
}
