package com.ziggfreed.common.ui.hud.command;

/**
 * What this family is CALLED, and the verbs it answers to.
 *
 * <p>A leaf on purpose: it imports nothing, so a consumer that wants to name the command in a help
 * line or a button can do so without dragging a command implementation along.
 *
 * <p><b>The named-arg form is not a style choice.</b> The engine's parser binds arguments by NAME,
 * so a positional line silently binds nothing.
 */
public final class HudCommandLine {

    /** The command family every HUD verb hangs off. */
    public static final String FAMILY = "zighud";

    /** Open the HUD settings page. */
    public static final String OPEN = "open";

    /** Put a panel at a named spot for yourself. */
    public static final String PLACE = "place";

    /** Hide a panel, or every panel, for yourself. */
    public static final String HIDE = "hide";

    /** Show a panel you hid, or every panel, for yourself. */
    public static final String SHOW = "show";

    /** Set the spot a panel sits at for everyone. */
    public static final String DEFAULT = "default";

    /** The word that clears a pick, on {@code place} and {@code default} alike. */
    public static final String SERVER_CHOICE = "server";

    /** The word that means every panel, on {@code hide} and {@code show}. */
    public static final String ALL = "all";

    private HudCommandLine() {
    }
}
