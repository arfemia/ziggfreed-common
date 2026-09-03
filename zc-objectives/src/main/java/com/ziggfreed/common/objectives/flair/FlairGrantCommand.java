package com.ziggfreed.common.objectives.flair;

import java.util.Locale;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;

/**
 * {@code grant --player=<name> --flair=<id>}: unlock a flair for a player, through the one write
 * path the reward kind uses, so an administrator's grant and a quest's payout are the same thing.
 *
 * <p><b>An id nothing names is granted anyway, with a warning.</b> The library has no flair
 * catalogue - what a flair is belongs to whichever mod renders it - and the only presence probe it
 * has is whether some loaded lang file names the flair ({@code flair.<id>.name}). A pack that ships
 * the flair may simply not have loaded yet, so refusing would turn a recoverable moment into a
 * lost grant; the sender is told instead, once, where they can see it.
 */
final class FlairGrantCommand extends FlairTargetCommand {

    private final OptionalArg<String> flairArg;

    FlairGrantCommand() {
        super(FlairCommandLine.GRANT);
        this.flairArg = flairArg();
    }

    @Override
    protected void execute(@Nonnull CommandContext ctx, @Nonnull Target target) {
        String flairId = flairId(ctx, flairArg);
        if (flairId == null) {
            return;
        }
        String id = flairId.toLowerCase(Locale.ROOT);
        if (!FlairText.isNamed(id)) {
            FlairAdminMessages.warned(ctx, "grant.unknown", id);
        }
        switch (FlairUnlocks.unlock(target.store(), target.ref(), target.playerRef(), id)) {
            case UNLOCKED -> FlairAdminMessages.done(ctx, "grant.done", target.name(), id);
            case ALREADY_UNLOCKED -> FlairAdminMessages.detail(ctx, "grant.already", target.name(), id);
            case NO_RECORD -> FlairAdminMessages.refused(ctx, "player.no_record", target.name());
            default -> FlairAdminMessages.refused(ctx, "flair.refused", id);
        }
    }
}
