package com.ziggfreed.common.objectives.title.command;

import javax.annotation.Nonnull;

/**
 * What the title family is CALLED: the family, its verbs and arguments, and the one console line the
 * reward kind hands a retry queue. A leaf that imports nothing, so anything that spells one of these
 * names can do so without dragging a command into the layer below. The engine binds arguments by
 * NAME, so {@link #grant} spells its flags out.
 */
public final class TitleCommandLine {

    /** The command family every title verb hangs off. */
    public static final String FAMILY = "zigtitle";

    /** Unlock a title for a player. */
    public static final String GRANT = "grant";

    /** Take a title away from a player. */
    public static final String REVOKE = "revoke";

    /** List the titles a player has unlocked. */
    public static final String LIST = "list";

    /** Open the title picker (a player verb). */
    public static final String OPEN = "open";

    /** The {@code --player} argument name. */
    public static final String ARG_PLAYER = "player";

    /** The {@code --title} argument name. */
    public static final String ARG_TITLE = "title";

    private TitleCommandLine() {
    }

    /** The console line that unlocks {@code titleId} for {@code playerName}, without the leading slash. */
    @Nonnull
    public static String grant(@Nonnull String playerName, @Nonnull String titleId) {
        return FAMILY + " " + GRANT + " --" + ARG_PLAYER + "=" + playerName
                + " --" + ARG_TITLE + "=" + titleId;
    }
}
