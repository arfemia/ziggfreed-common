package com.ziggfreed.common.encounter.command;

/**
 * What this family is CALLED, and the argument names it binds. A leaf on purpose: it imports
 * nothing. The engine's parser binds optional arguments by NAME ({@code --ref=...}), so a positional
 * line silently binds nothing.
 */
public final class EncounterCommandLine {

    /** The command family every encounter admin verb hangs off. */
    public static final String FAMILY = "zigencounter";

    public static final String LIST = "list";
    public static final String INSPECT = "inspect";
    public static final String SPAWN = "spawn";
    public static final String END = "end";
    public static final String STATE = "state";
    public static final String VALIDATE = "validate";
    public static final String RELOAD = "reload";

    /** A live encounter: a run id prefix or a script id. */
    public static final String ARG_REF = "ref";
    public static final String ARG_ASSET = "asset";
    public static final String ARG_WORLD = "world";
    public static final String ARG_X = "x";
    public static final String ARG_Y = "y";
    public static final String ARG_Z = "z";
    public static final String ARG_STATE = "state";
    public static final String ARG_SUBSTATE = "substate";

    private EncounterCommandLine() {
    }
}
