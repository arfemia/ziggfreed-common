package com.ziggfreed.common.objectives.flair;

import javax.annotation.Nonnull;

/**
 * What the flair family is CALLED: the family and its three verbs, plus the one console line the
 * reward kind hands a retry queue.
 *
 * <p>A leaf on purpose: it imports nothing, so anything that needs to spell one of these names - a
 * consumer's alias, a help line, a queued retry, a test - can name it without dragging a command
 * implementation into the layer below. Each name has exactly one owner, which is the command
 * registered under it.
 *
 * <p><b>The named-arg form is not a style choice.</b> The engine's parser binds arguments by NAME,
 * so a positional line silently binds nothing; {@link #grant} therefore spells the flags out.
 */
public final class FlairCommandLine {

    /** The command family every flair admin verb hangs off. */
    public static final String FAMILY = "zigflair";

    /** Unlock a flair for a player. */
    public static final String GRANT = "grant";

    /** Take a flair away from a player. */
    public static final String REVOKE = "revoke";

    /** List the flairs a player has unlocked. */
    public static final String LIST = "list";

    /** The {@code --player} argument name. */
    public static final String ARG_PLAYER = "player";

    /** The {@code --flair} argument name. */
    public static final String ARG_FLAIR = "flair";

    private FlairCommandLine() {
    }

    /**
     * The console line that unlocks {@code flairId} for {@code playerName}, without the leading
     * slash, the form a retry queue runs later.
     */
    @Nonnull
    public static String grant(@Nonnull String playerName, @Nonnull String flairId) {
        return FAMILY + " " + GRANT + " --" + ARG_PLAYER + "=" + playerName
                + " --" + ARG_FLAIR + "=" + flairId;
    }
}
