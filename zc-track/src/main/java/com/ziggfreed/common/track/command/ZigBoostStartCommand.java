package com.ziggfreed.common.track.command;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.command.AbstractTargetPlayerCommand;
import com.ziggfreed.common.track.boost.BoostText;
import com.ziggfreed.common.track.boost.Boosts;

/**
 * {@code start [--player=<name>] --boost=<id> [--percent=<n>] [--seconds=<n>]}: start a boost for an online player
 * through the one starter a boost item and a reward use ({@code Boosts.start}), so the limits, the wait, the season
 * and the {@code Requires} all apply; a server boost lifts everyone. The line a Boost reward's Claim replays where
 * the reward kind itself cannot pay. Left out, the sender is the player.
 */
final class ZigBoostStartCommand extends AbstractTargetPlayerCommand<TrackTarget> {

    /** What a start from here names as its starter. */
    static final String BY = "admin";

    private final OptionalArg<String> boostArg;
    private final OptionalArg<Double> percentArg;
    private final OptionalArg<Integer> secondsArg;

    ZigBoostStartCommand() {
        super(ZigBoostCommand.START, BoostCommandMessages.desc(ZigBoostCommand.START),
                BoostCommandMessages.desc("arg.player"), BoostCommandMessages::refused, false);
        this.boostArg = withOptionalArg("boost", BoostCommandMessages.desc("arg.boost"), ArgTypes.STRING);
        this.percentArg = withOptionalArg("percent", BoostCommandMessages.desc("arg.percent"), ArgTypes.DOUBLE);
        this.secondsArg = withOptionalArg("seconds", BoostCommandMessages.desc("arg.seconds"), ArgTypes.INTEGER);
    }

    @Override
    @Nonnull
    protected TrackTarget buildTarget(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef) {
        return new TrackTarget(playerRef.getUuid(), playerRef.getUsername());
    }

    @Override
    protected void execute(@Nonnull CommandContext ctx, @Nonnull TrackTarget target) {
        String typed = boostArg.provided(ctx) ? boostArg.get(ctx) : null;
        if (typed == null || typed.isBlank()) {
            BoostCommandMessages.refused(ctx, "boost.needed");
            return;
        }
        Double percent = percentArg.provided(ctx) ? percentArg.get(ctx) : null;
        Integer seconds = secondsArg.provided(ctx) ? secondsArg.get(ctx) : null;
        Boosts.Start start = Boosts.start(target.id(), typed.trim(), percent,
                seconds == null ? null : (long) Math.max(0, seconds), BY);
        if (start.started() && start.boost() != null) {
            BoostCommandMessages.done(ctx, "start.done", BoostText.name(start.boost()), target.name());
            return;
        }
        BoostCommandMessages.refused(ctx, "start.refused", target.name());
        Message reason = BoostText.refusal(start);
        if (reason != null) {
            BoostCommandMessages.refusedLine(ctx, reason);
        }
    }
}
