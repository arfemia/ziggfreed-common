package com.ziggfreed.common.almanac.command;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider;
import com.ziggfreed.common.almanac.AlmanacText;

/**
 * {@code /zigalmanac} - the Almanac, from the command line.
 *
 * <pre>
 * /zigalmanac open [--event=&lt;id&gt;]     open the Almanac, on one season or on the one on now
 * </pre>
 *
 * <p>A PLAYER's family: it opens the caller's own screen, so it sits in the engine's adventurer group
 * and every player holds it, the way {@code /zighud open} does. It is the way in on a server whose mods
 * give no menu button of their own.
 */
public final class ZigAlmanacCommand extends AbstractCommandCollection {

    public ZigAlmanacCommand() {
        super(AlmanacCommandLine.FAMILY, AlmanacText.desc("family"));
        setPermissionGroups(HytalePermissionsProvider.GROUP_ADVENTURER);
        addSubCommand(new AlmanacOpenCommand());
    }
}
