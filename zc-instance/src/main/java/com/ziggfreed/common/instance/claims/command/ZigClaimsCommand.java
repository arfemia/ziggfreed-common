package com.ziggfreed.common.instance.claims.command;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/**
 * {@code /zigclaims} - the admin surface for players' Claims.
 *
 * <pre>
 * /zigclaims give [--player=&lt;name&gt;] --lootable=&lt;id&gt;                    roll a loot table into a player's Claims
 * /zigclaims chest [--player=&lt;name&gt;] [--chest=&lt;id&gt;] [--lootable=&lt;id&gt;]  stand a reward chest by a player
 * </pre>
 *
 * <p>No permission check is written here: the engine derives one node per verb
 * ({@code ziggfreed.ziggfreedcommon.command.zigclaims.give}, {@code ...zigclaims.chest}) that nobody holds until a
 * server grants it, and the console holds everything.
 */
public final class ZigClaimsCommand extends AbstractCommandCollection {

    /** The family's name. */
    static final String FAMILY = "zigclaims";

    /** Roll a loot table into a player's Claims. */
    static final String GIVE = "give";

    /** Stand a reward chest by a player. */
    static final String CHEST = "chest";

    public ZigClaimsCommand() {
        super(FAMILY, ClaimsCommandMessages.desc("family"));
        addSubCommand(new ZigClaimsGiveCommand());
        addSubCommand(new ZigClaimsChestCommand());
    }
}
