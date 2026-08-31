package com.ziggfreed.common.npc.placement.command;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/**
 * {@code /zignpc} - the admin surface for the NPC placements on this server.
 *
 * <h2>The verbs</h2>
 *
 * <pre>
 * /zignpc place --role=&lt;role&gt; [--id=&lt;id&gt;] [--dialogue=&lt;id&gt;] [--world=&lt;name&gt;]
 *                                       stand that role where you are, and keep it there
 * </pre>
 *
 * <p>Arguments bind by NAME, never by position - that is the engine's parser, not a house style.
 *
 * <h2>Permissions</h2>
 *
 * <p>There is no permission check written anywhere in this family, and that is the point: the engine
 * derives one node per command from the plugin and the command name, registers it, and refuses the
 * call before a body runs. So the nodes are
 * {@code ziggfreed.ziggfreedcommon.command.zignpc} for the family and
 * {@code ziggfreed.ziggfreedcommon.command.zignpc.<verb>} for each verb, a sub-command needs BOTH,
 * and nobody holds either until a server grants it. The console holds everything.
 *
 * <h2>Why this family belongs to the library</h2>
 *
 * <p>The module that owns an engine owns the commands that drive it. The placement engine, its
 * ledger, its gate chain and its owner file are all here, so a consumer mod wanting {@code /mmonpc}
 * registers an alias that calls straight through, rather than a second implementation that can
 * disagree with this one. It is also what gives every OTHER consumer of the placement engine an
 * admin surface without shipping one.
 */
public final class ZigNpcCommand extends AbstractCommandCollection {

    public ZigNpcCommand() {
        super(NpcCommandLine.FAMILY, NpcAdminMessages.desc("family"));
        addSubCommand(new NpcPlaceCommand());
    }
}
