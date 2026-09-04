package com.ziggfreed.common.factor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Where a player's lifetime TALLIES live, for the {@code ziggfreedcommon:counter} reading: the
 * seam the mod that keeps them fills, so a requirement anywhere can ask "how often has this player
 * done this" without the library knowing what a statistics record is.
 *
 * <p>Asked with the factor's own context, whose live subject is the player, and the flat counter
 * key exactly as the {@code Param} spelled it: a plain key for a grand total ({@code mob_kills}), or
 * a category and a name joined by {@code /} for one line of a breakdown ({@code mob_kills/Warden}).
 * Answer the tally, {@code 0} for a player who has never done the thing, and {@code null} when this
 * source cannot say anything about the question (no record for that player, a subject that is not
 * a player at all).
 */
@FunctionalInterface
public interface CounterSource {

    /** The tally under {@code key} for the player in {@code ctx}, or null when this source has no answer. */
    @Nullable
    Long count(@Nonnull FactorContext ctx, @Nonnull String key);
}
