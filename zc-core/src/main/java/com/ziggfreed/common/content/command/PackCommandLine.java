package com.ziggfreed.common.content.command;

/**
 * What this family is CALLED. A leaf on purpose: it imports nothing.
 */
public final class PackCommandLine {

    /** The command family every server content pack verb hangs off. */
    public static final String FAMILY = "zigpack";

    public static final String EXPORT = "export";

    public static final String STATUS = "status";

    private PackCommandLine() {
    }
}
