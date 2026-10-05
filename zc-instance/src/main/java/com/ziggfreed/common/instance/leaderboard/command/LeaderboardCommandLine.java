package com.ziggfreed.common.instance.leaderboard.command;

/**
 * What this family is CALLED, and the argument names it binds. A leaf on purpose: it imports nothing,
 * so a consumer can name the command in a help line or a button without dragging a command along. The
 * engine's parser binds an optional argument by NAME ({@code --encounter=...}), so a positional line
 * silently binds nothing.
 */
public final class LeaderboardCommandLine {

    /** The command family every records verb hangs off. */
    public static final String FAMILY = "zigleaderboard";

    /** Open a boss fight's records. */
    public static final String ENCOUNTER = "encounter";

    /** The fight to open: its encounter script id. */
    public static final String ARG_ENCOUNTER = "encounter";

    private LeaderboardCommandLine() {
    }
}
