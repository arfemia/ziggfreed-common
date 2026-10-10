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

    /** Move the stat board's clock, an owner's verb for trying a week's or a month's turnover. */
    public static final String CLOCK = "clock";

    /** How far to move it, positional: {@code 7d}, {@code 12h}, {@code 30m}, {@code -1d} or {@code 0}. */
    public static final String ARG_OFFSET = "offset";

    private LeaderboardCommandLine() {
    }
}
