package com.ziggfreed.common.objectives.title.command;

import java.util.List;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.ziggfreed.common.objectives.title.TitleUnlocks;

/**
 * {@code list [--player=<name>]}: every title the player has unlocked, sorted, as the ids a grant or
 * a revoke would be typed with, the shown one marked. Ids are printed raw on purpose.
 */
final class TitleListCommand extends TitleTargetCommand {

    TitleListCommand() {
        super(TitleCommandLine.LIST);
    }

    @Override
    protected void execute(@Nonnull CommandContext ctx, @Nonnull Target target) {
        List<String> ids = TitleUnlocks.unlocked(target.store(), target.ref());
        if (ids.isEmpty()) {
            TitleCommandMessages.detail(ctx, "list.none", target.name());
            return;
        }
        String shown = TitleUnlocks.active(target.store(), target.ref());
        TitleCommandMessages.heading(ctx, "list.header", target.name(), ids.size());
        for (String id : ids) {
            TitleCommandMessages.detail(ctx, id.equals(shown) ? "list.row_shown" : "list.row", id);
        }
    }
}
