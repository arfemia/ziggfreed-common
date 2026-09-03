package com.ziggfreed.common.objectives.flair;

import java.util.List;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;

/**
 * {@code list [--player=<name>]}: every flair the player has unlocked, sorted, as the machine ids a
 * grant or a revoke would be typed with. The ids are printed raw on purpose: a translated name
 * would stop naming the thing the next command has to spell.
 */
final class FlairListCommand extends FlairTargetCommand {

    FlairListCommand() {
        super(FlairCommandLine.LIST);
    }

    @Override
    protected void execute(@Nonnull CommandContext ctx, @Nonnull Target target) {
        List<String> ids = FlairUnlocks.unlocked(target.store(), target.ref());
        if (ids.isEmpty()) {
            FlairAdminMessages.detail(ctx, "list.none", target.name());
            return;
        }
        FlairAdminMessages.heading(ctx, "list.header", target.name(), ids.size());
        for (String id : ids) {
            FlairAdminMessages.detail(ctx, "list.row", id);
        }
    }
}
