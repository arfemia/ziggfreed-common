package com.ziggfreed.common.content.command;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/**
 * {@code /zigpack} - the admin surface over this server's own content pack, where everything made in
 * game is saved.
 *
 * <pre>
 * /zigpack status     where the pack is, whether it can be saved to, and how much it holds
 * /zigpack export     zip the pack and its text as a content pack for another server
 * </pre>
 *
 * <p>No permission check is written in this family: the engine derives one node per verb from the
 * plugin and the command name ({@code ziggfreed.ziggfreedcommon.command.zigpack.<verb>}) and refuses
 * the call before a body runs. The console holds everything.
 */
public final class ZigPackCommand extends AbstractCommandCollection {

    public ZigPackCommand() {
        super(PackCommandLine.FAMILY, PackAdminMessages.desc("family"));
        addSubCommand(new PackStatusCommand());
        addSubCommand(new PackExportCommand());
    }
}
