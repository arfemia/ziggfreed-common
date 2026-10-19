package com.ziggfreed.common.track.command;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/**
 * {@code /zigboost} - the admin surface for boosts.
 *
 * <pre>
 * /zigboost start [--player=&lt;name&gt;] --boost=&lt;id&gt; [--percent=&lt;n&gt;] [--seconds=&lt;n&gt;]   start a boost, as a token would
 * </pre>
 *
 * <p>No permission check is written here: the engine derives one node per verb
 * ({@code ziggfreed.ziggfreedcommon.command.zigboost.start}) that nobody holds until a server grants it, and the
 * console holds everything. {@code start} is also the line a Boost reward's Claim replays where the reward kind
 * itself cannot pay.
 */
public final class ZigBoostCommand extends AbstractCommandCollection {

    /** The family's name. */
    public static final String FAMILY = "zigboost";

    /** Start a boost. */
    public static final String START = "start";

    public ZigBoostCommand() {
        super(FAMILY, BoostCommandMessages.desc("family"));
        addSubCommand(new ZigBoostStartCommand());
    }
}
