package com.ziggfreed.common.track.command;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.ziggfreed.common.track.TrackChange;
import com.ziggfreed.common.track.TrackDef;
import com.ziggfreed.common.track.TrackKey;
import com.ziggfreed.common.track.TrackService;
import com.ziggfreed.common.track.TrackText;

/**
 * {@code add [--player=<name>] --track=<id> --amount=<n>}: move an online player's climb by {@code n} (negative
 * to take some away), through the one write path a reward takes ({@code TrackService.add}): the gain seam, the
 * cap, the unlock Claims, the mirror and the change event all apply. The line a Track reward's Claim replays
 * where the reward kind itself cannot pay. Left out, the sender is the player.
 */
final class ZigTrackAddCommand extends TrackTargetCommand {

    /** What an add moves by when it names its source. */
    static final String SOURCE = "admin";

    private final OptionalArg<Double> amountArg;

    ZigTrackAddCommand() {
        super(ZigTrackCommand.ADD, false);
        this.amountArg = withOptionalArg("amount", TrackCommandMessages.desc("arg.amount"), ArgTypes.DOUBLE);
    }

    @Override
    protected void execute(@Nonnull CommandContext ctx, @Nonnull TrackTarget target) {
        TrackDef def = namedTrack(ctx);
        if (def == null) {
            return;
        }
        Double amount = amountArg.provided(ctx) ? amountArg.get(ctx) : null;
        if (amount == null || !Double.isFinite(amount) || amount == 0.0) {
            TrackCommandMessages.refused(ctx, "amount.needed");
            return;
        }
        TrackService.Result result = TrackService.add(TrackKey.player(target.id(), def.asset().getId()), amount,
                SOURCE);
        TrackChange change = result.change();
        switch (result.status()) {
            case CHANGED -> TrackCommandMessages.done(ctx, "add.done", TrackText.name(def.asset()), target.name(),
                    change == null ? 0L : change.delta(), change == null ? 1 : change.levelAfter(),
                    change == null ? 0 : change.claims());
            case UNCHANGED -> TrackCommandMessages.detail(ctx, "add.unchanged", TrackText.name(def.asset()),
                    target.name());
            case LOCKED -> TrackCommandMessages.refused(ctx, "add.locked", TrackText.name(def.asset()), target.name());
            case NOT_LIVE -> TrackCommandMessages.refused(ctx, "player.offline");
            default -> TrackCommandMessages.refused(ctx, "track.unknown", def.asset().getId());
        }
    }
}
