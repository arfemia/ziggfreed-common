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
 * /zigtrack respec [--player=&lt;name&gt;] --track=&lt;id&gt;              clear every pick on a track
 * </pre>
 *
 * <p>No permission check is written here: the engine derives one node per verb
 * ({@code ziggfreed.ziggfreedcommon.command.zigtrack.add}, ...) that nobody holds until a server grants it, and
 * the console holds everything. {@code add} is also the line a Track reward's Claim replays where the reward
 * kind itself cannot pay.
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

    /** Make a pick. */
    static final String PICK = "pick";

    /** Undo the latest pick. */
    static final String UNDO = "undo";

    /** Clear a track's picks. */
    static final String RESPEC = "respec";

    public ZigTrackCommand() {
        super(FAMILY, TrackCommandMessages.desc("family"));
        addSubCommand(new ZigTrackAddCommand());
        addSubCommand(new ZigTrackShowCommand());
        addSubCommand(new ZigTrackPrestigeCommand());
        addSubCommand(new ZigTrackPickCommand());
        addSubCommand(new ZigTrackUndoCommand());
        addSubCommand(new ZigTrackRespecCommand());
    }
}
