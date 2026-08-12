package com.ziggfreed.common.loot.reward;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * How a compact authored TOKEN turns into a {@link RewardSpec} - the authoring-time half of a
 * reward kind, beside the {@link RewardHandler} that is its runtime half.
 *
 * <p>Some authoring formats are terse by necessity: a list of strings in a JSON field has no room
 * for a nested object, so a reward gets written as {@code "xp MINING 500"} and something has to know
 * that the middle word is a skill and that the whole line means "run this command". That knowledge
 * belongs to the mod that owns the kind, not to the parser reading the line, and this interface is
 * where the mod puts it.
 *
 * <p>Registering one is OPTIONAL. A kind that is only ever authored as a structured
 * {@code {Kind, Params}} object needs none - the parser already has everything it needs. Register an
 * authoring adapter only when the kind also has to be writable as a single word plus an argument.
 *
 * <p>Whatever a token expands to, the resulting spec's own kind is what the payout looks up, so a
 * token may expand into a completely different kind than its own name.
 */
@FunctionalInterface
public interface RewardAuthoring {

    /**
     * Expand one token's argument into the spec it means, or null when the argument makes no sense
     * for this token (the line is then skipped rather than paying out something wrong).
     *
     * @param arg the single word written after the token
     */
    @Nullable
    RewardSpec expand(@Nonnull String arg);
}
