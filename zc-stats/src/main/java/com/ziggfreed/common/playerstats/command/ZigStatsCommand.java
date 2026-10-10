package com.ziggfreed.common.playerstats.command;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/**
 * {@code /zigstats} - the admin surface for the player stat store.
 *
 * <pre>
 * /zigstats show [--player=&lt;name&gt;]   every tally a player holds, by its key
 * </pre>
 *
 * <p>No permission check is written here: the engine derives one node per verb
 * ({@code ziggfreed.ziggfreedcommon.command.zigstats.<verb>}) that nobody holds until a server grants
 * it, and the console holds everything.
 */
public final class ZigStatsCommand extends AbstractCommandCollection {

    /** The command family. */
    public static final String FAMILY = "zigstats";

    public ZigStatsCommand() {
        super(FAMILY, StatsCommandMessages.desc("family"));
        addSubCommand(new StatsShowCommand());
    }
}
