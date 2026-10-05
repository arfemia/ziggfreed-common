package com.ziggfreed.common.instance.leaderboard.command;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider;

/**
 * {@code /zigleaderboard} - the library's records screens, from the command line.
 *
 * <pre>
 * /zigleaderboard encounter [--encounter=&lt;script id&gt;]   open a boss fight's records
 * </pre>
 *
 * <p>A PLAYER's family: it only opens a screen for the caller, so it sits in the engine's adventurer
 * group and every player holds it. It belongs to the library because the board it reads does: a
 * server running nothing but this library still keeps its boss fights' records, and still gets a way
 * to read them.
 */
public final class ZigLeaderboardCommand extends AbstractCommandCollection {

    public ZigLeaderboardCommand() {
        super(LeaderboardCommandLine.FAMILY, LeaderboardCommandMessages.desc("family"));
        setPermissionGroups(HytalePermissionsProvider.GROUP_ADVENTURER);
        addSubCommand(new EncounterLeaderboardCommand());
    }
}
