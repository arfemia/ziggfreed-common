package com.ziggfreed.common.objectives.title.command;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/**
 * {@code /zigtitle} - the admin surface for a player's titles, and every player's way in to the
 * title picker.
 *
 * <pre>
 * /zigtitle grant  --player=&lt;name&gt; --title=&lt;id&gt;   unlock a title for a player
 * /zigtitle revoke --player=&lt;name&gt; --title=&lt;id&gt;   take a title away from a player
 * /zigtitle list  [--player=&lt;name&gt;]                the titles a player has unlocked, as ids
 * /zigtitle open                                  open the title picker (every player)
 * </pre>
 *
 * <p>No permission check is written here: the engine derives one node per verb
 * ({@code ziggfreed.ziggfreedcommon.command.zigtitle.<verb>}) that nobody holds until a server grants
 * it, and the console holds everything, which is what lets a retry queue run the grant line. The one
 * exception is {@code open}, a player's verb in the engine's adventurer group: a verb with its own
 * permission group skips its family's node, so every player can open their own picker. Every write,
 * the picker's included, goes through {@code TitleUnlocks}, the path the {@code Title} reward kind
 * pays through.
 */
public final class ZigTitleCommand extends AbstractCommandCollection {

    public ZigTitleCommand() {
        super(TitleCommandLine.FAMILY, TitleCommandMessages.desc("family"));
        addSubCommand(new TitleGrantCommand());
        addSubCommand(new TitleRevokeCommand());
        addSubCommand(new TitleListCommand());
        addSubCommand(new TitleOpenCommand());
    }
}
