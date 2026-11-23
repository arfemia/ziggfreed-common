package com.ziggfreed.common.track.command;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/**
 * {@code /zigtrack} - the admin surface for players' climbs.
 *
 * <pre>
 * /zigtrack add [--player=&lt;name&gt;] --track=&lt;id&gt; --amount=&lt;n&gt;   move a climb, as a reward would
 * /zigtrack show [--player=&lt;name or id&gt;] [--track=&lt;id&gt;]         read a climb, online or not
 * /zigtrack prestige [--player=&lt;name&gt;] --track=&lt;id&gt;            start a climb again
 * /zigtrack pick [--player=&lt;name&gt;] --track=&lt;id&gt; --option=&lt;id&gt; [--level=&lt;n&gt;] | --pick=&lt;id&gt;
 *                                                         make a pick, as the track's own page would
 * /zigtrack undo [--player=&lt;name&gt;]                         undo the latest pick still in its window
 * /zigtrack respec [--player=&lt;name&gt;] --track=&lt;id&gt;              clear every pick a trainer did not teach
 * /zigtrack savebuild [--player=&lt;name&gt;] --track=&lt;id&gt; --slot=&lt;n&gt;   save the picks in use as a build
 * /zigtrack swapbuild [--player=&lt;name&gt;] --track=&lt;id&gt; --slot=&lt;n&gt; [--force]
 *                                                         swap to a saved build, at its price (--force: even when
 *                                                         the picks in use are in no saved build, losing them)
 * /zigtrack builds [--player=&lt;name&gt;] --track=&lt;id&gt;              list the saved builds
 * /zigtrack respend [--player=&lt;name&gt;] --track=&lt;id&gt;             spend remembered takes again, in order
 * /zigtrack starter [--player=&lt;name&gt;] --track=&lt;id&gt; [--points=&lt;n&gt;]
 *                                                         spend what is left of the suggested build, in order
 *                                                         (--points: only its first n points)
 * /zigtrack lamp [--player=&lt;name&gt;] --price=&lt;id&gt; | --minutes=&lt;n&gt; [--level=&lt;n&gt;] [--maxlevel=&lt;n&gt;]
 *                [--picks=&lt;n&gt;] [--tracks=&lt;id,id&gt;] [--category=&lt;id&gt;] [--minlevel=&lt;n&gt;] [--quantity=&lt;n&gt;]
 *                                                         hand lamps of minutes of play the player points at tracks
 * /zigtrack stance list|hold|release [--player=&lt;name&gt;] --stance=&lt;id&gt; [--face=&lt;pick&gt;]
 *                                                         a player's faces held in their stances: their own for any
 *                                                         player (the adventurer group), another's for an operator
 * </pre>
 *
 * <p>No permission check is written here: the engine derives one node per verb
 * ({@code ziggfreed.ziggfreedcommon.command.zigtrack.add}, ...) that nobody holds until a server grants it, and
 * the console holds everything. {@code add} is also the line a Track reward's Claim replays where the reward
 * kind itself cannot pay, and {@code lamp} a Track_Lamp reward's.
 */
public final class ZigTrackCommand extends AbstractCommandCollection {

    /** The family's name. */
    public static final String FAMILY = "zigtrack";

    /** Move a climb. */
    public static final String ADD = "add";

    /** Read a climb. */
    static final String SHOW = "show";

    /** Start a climb again. */
    static final String PRESTIGE = "prestige";

    /** Make a pick (and with {@code --pick} alone, an admin's teach: the line a Pick reward's Claim replays). */
    public static final String PICK = "pick";

    /** Undo the latest pick. */
    static final String UNDO = "undo";

    /** Clear a track's picks. */
    static final String RESPEC = "respec";

    /** Save the picks in use as a build. */
    static final String SAVE_BUILD = "savebuild";

    /** Swap to a saved build. */
    static final String SWAP_BUILD = "swapbuild";

    /** List the saved builds. */
    static final String BUILDS = "builds";

    /** Spend the remembered takes again. */
    static final String RESPEND = "respend";

    /** Spend what is left of the suggested build. */
    static final String STARTER = "starter";

    /** Hand lamps. */
    public static final String LAMP = "lamp";

    public ZigTrackCommand() {
        super(FAMILY, TrackCommandMessages.desc("family"));
        addSubCommand(new ZigTrackAddCommand());
        addSubCommand(new ZigTrackShowCommand());
        addSubCommand(new ZigTrackPrestigeCommand());
        addSubCommand(new ZigTrackPickCommand());
        addSubCommand(new ZigTrackUndoCommand());
        addSubCommand(new ZigTrackRespecCommand());
        addSubCommand(new ZigTrackSaveBuildCommand());
        addSubCommand(new ZigTrackSwapBuildCommand());
        addSubCommand(new ZigTrackBuildsCommand());
        addSubCommand(new ZigTrackRespendCommand());
        addSubCommand(new ZigTrackStarterCommand());
        addSubCommand(new ZigTrackLampCommand());
        addSubCommand(new ZigTrackStanceCommand());
    }
}
