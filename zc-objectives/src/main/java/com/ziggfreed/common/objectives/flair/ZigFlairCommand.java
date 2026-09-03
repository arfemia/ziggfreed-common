package com.ziggfreed.common.objectives.flair;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/**
 * {@code /zigflair} - the admin surface for a player's cosmetic flairs: the per-player set the
 * library persists ({@code ZigFlairComponent}) and the one write path onto it
 * ({@link FlairUnlocks}).
 *
 * <pre>
 * /zigflair grant  --player=&lt;name&gt; --flair=&lt;id&gt;   unlock a flair for a player
 * /zigflair revoke --player=&lt;name&gt; --flair=&lt;id&gt;   take a flair away from a player
 * /zigflair list  [--player=&lt;name&gt;]                the flairs a player has unlocked, as ids
 * </pre>
 *
 * <p>Arguments bind by NAME, never by position. A verb defaults to the sender when {@code --player}
 * is left out, and needs the player ONLINE: their flairs live on their own entity, so an offline
 * edit has nowhere to land (a reward owed to somebody offline rides the consumer's retry queue as
 * the {@code grant} line above).
 *
 * <h2>Permissions</h2>
 *
 * <p>No permission check is written anywhere in this family, and that is the point: the engine
 * derives one node per command from the plugin and the command name, registers it, and refuses the
 * call before a body runs. The nodes are {@code ziggfreed.ziggfreedcommon.command.zigflair} for the
 * family and {@code ...zigflair.<verb>} per verb; nobody holds any of them until a server grants
 * it, and the console holds everything, which is what lets a retry queue run the grant line.
 *
 * <h2>Why this family belongs to the library</h2>
 *
 * <p>The module that owns a write path owns the commands that drive it. Every verb here writes
 * through {@link FlairUnlocks}, the same path the {@code Flair} reward kind pays through, so a
 * consumer mod wanting {@code /myflair} registers an alias that calls straight through rather than
 * a second implementation that can disagree with this one about what an unlock announces.
 */
public final class ZigFlairCommand extends AbstractCommandCollection {

    public ZigFlairCommand() {
        super(FlairCommandLine.FAMILY, FlairAdminMessages.desc("family"));
        addSubCommand(new FlairGrantCommand());
        addSubCommand(new FlairRevokeCommand());
        addSubCommand(new FlairListCommand());
    }
}
