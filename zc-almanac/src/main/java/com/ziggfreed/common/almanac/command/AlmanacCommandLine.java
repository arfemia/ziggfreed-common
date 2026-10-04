package com.ziggfreed.common.almanac.command;

/**
 * What the Almanac's command family is CALLED, and its verb. A leaf on purpose: it imports nothing, so
 * a consumer naming the command in a help line drags no command along. Arguments bind by NAME.
 */
public final class AlmanacCommandLine {

    /** The family every Almanac verb hangs off. */
    public static final String FAMILY = "zigalmanac";

    /** Open the Almanac. */
    public static final String OPEN = "open";

    private AlmanacCommandLine() {
    }
}
