package com.ziggfreed.common.track.command;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/**
 * {@code /zigboost} - the admin surface for boosts.
 *
 * <pre>
 * /zigboost start [--player=&lt;name&gt;] --boost=&lt;id&gt; [--percent=&lt;n&gt;] [--seconds=&lt;n&gt;] [--track=&lt;id&gt;]
 *                                                                        start a boost, as a token would
 * /zigboost give [--player=&lt;name&gt;] --boost=&lt;id&gt; [--percent=&lt;n&gt;] [--seconds=&lt;n&gt;] [--track=&lt;id&gt;]
 *                [--quantity=&lt;n&gt;]                                       hand boost tokens (Zig_Boost_Token)
 * /zigboost own [--boost=&lt;id&gt;]                                          start a reusable boost of your own,
 *                                                                        or list them (every player)
 * </pre>
 *
 * <p>No permission check is written here: the engine derives one node per verb
 * ({@code ziggfreed.ziggfreedcommon.command.zigboost.start}, {@code .give}) that nobody holds until a server grants
 * it, and the console holds everything; {@code own} sits in the adventurer group, so every player may use it for
 * themselves. {@code start} is also the line a Boost reward's Claim replays where the reward kind itself cannot pay.
 */
public final class ZigBoostCommand extends AbstractCommandCollection {

    /** The family's name. */
    public static final String FAMILY = "zigboost";

    /** Start a boost. */
    public static final String START = "start";

    /** Hand boost tokens. */
    public static final String GIVE = "give";

    /** Start a reusable boost of the caller's own. */
    public static final String OWN = "own";

    public ZigBoostCommand() {
        super(FAMILY, BoostCommandMessages.desc("family"));
        addSubCommand(new ZigBoostStartCommand());
        addSubCommand(new ZigBoostGiveCommand());
        addSubCommand(new ZigBoostOwnCommand());
    }
}
