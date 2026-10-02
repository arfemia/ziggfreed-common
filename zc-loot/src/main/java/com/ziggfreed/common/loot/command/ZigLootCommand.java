package com.ziggfreed.common.loot.command;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/**
 * {@code /zigloot} - the admin surface over this server's loot tables.
 *
 * <pre>
 * /zigloot validate     audit every loaded loot table against the reward kinds this server pays
 * </pre>
 *
 * <p>There is no permission check written anywhere in this family, and that is the point: the
 * engine derives one node per command from the plugin and the command name
 * ({@code ziggfreed.ziggfreedcommon.command.zigloot[.<verb>]}), registers it, and refuses the call
 * before a body runs. The console holds everything, which is what makes every verb usable from a
 * startup script and a headless boot check.
 */
public final class ZigLootCommand extends AbstractCommandCollection {

    public ZigLootCommand() {
        super(LootCommandLine.FAMILY, LootAdminMessages.desc("family"));
        addSubCommand(new LootValidateCommand());
    }
}
