package com.ziggfreed.common.instance.claims.command;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/**
 * {@code /zigclaims} - the admin surface for players' Claims.
 *
 * <pre>
 * /zigclaims give [--player=&lt;name&gt;] --lootable=&lt;id&gt;   roll a loot table into a player's Claims
 * </pre>
 *
 * <p>No permission check is written here: the engine derives one node per verb
 * ({@code ziggfreed.ziggfreedcommon.command.zigclaims.give}) that nobody holds until a server grants it, and the
 * console holds everything.
 */
public final class ZigClaimsCommand extends AbstractCommandCollection {

    /** The family's name. */
    static final String FAMILY = "zigclaims";

    /** The one verb. */
    static final String GIVE = "give";

    public ZigClaimsCommand() {
        super(FAMILY, ClaimsCommandMessages.desc("family"));
        addSubCommand(new ZigClaimsGiveCommand());
    }
}
