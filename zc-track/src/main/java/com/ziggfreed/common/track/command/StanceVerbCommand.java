package com.ziggfreed.common.track.command;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.command.AbstractTargetPlayerCommand;
import com.ziggfreed.common.owner.ServerOperators;
import com.ziggfreed.common.track.stance.TrackStanceAsset;
import com.ziggfreed.common.track.stance.TrackStanceConfig;

/**
 * The half every {@code /zigtrack stance} verb shares: a PLAYER's verb in the admin family, in the engine's adventurer
 * group, which every player holds, so a player lists, holds and releases their own faces by the stance's own rules (its
 * place, its prices, never in a fight). Naming another player ({@code --player}) takes a server operator
 * ({@code ServerOperators}, the game's own operator group), and acts as an admin does: anywhere, free, in a fight or
 * not. The console names a player and acts as an admin.
 */
abstract class StanceVerbCommand extends AbstractTargetPlayerCommand<TrackTarget> {

    private final OptionalArg<String> stanceArg;

    StanceVerbCommand(@Nonnull String verb) {
        super(verb, TrackCommandMessages.desc("stance." + verb), TrackCommandMessages.desc("arg.stance.player"),
                TrackCommandMessages::refused);
        setPermissionGroups(HytalePermissionsProvider.GROUP_ADVENTURER);
        this.stanceArg = withOptionalArg("stance", TrackCommandMessages.desc("arg.stance"), ArgTypes.STRING);
    }

    @Override
    @Nonnull
    protected final TrackTarget buildTarget(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef) {
        return new TrackTarget(playerRef.getUuid(), playerRef.getUsername());
    }

    @Override
    protected final void execute(@Nonnull CommandContext ctx, @Nonnull TrackTarget target) {
        boolean self = ctx.isPlayer() && target.id().equals(ctx.sender().getUuid());
        if (!self && ctx.isPlayer() && !ServerOperators.isOperator(ctx.sender().getUuid())) {
            TrackCommandMessages.refused(ctx, "stance.others");
            return;
        }
        run(ctx, target, !self);
    }

    /**
     * Do the verb for {@code target}, on their world thread.
     *
     * @param admin true for an operator or the console acting on another player: anywhere, free, in a fight or not
     */
    protected abstract void run(@Nonnull CommandContext ctx, @Nonnull TrackTarget target, boolean admin);

    /** The {@code --stance} as typed and trimmed, or null when none was. */
    @Nullable
    protected final String typedStance(@Nonnull CommandContext ctx) {
        return TrackTargetCommand.typed(ctx, stanceArg);
    }

    /** The switched-on stance {@code --stance} names, or null after refusing (none named, or no such stance here). */
    @Nullable
    protected final TrackStanceAsset namedStance(@Nonnull CommandContext ctx) {
        String typed = typedStance(ctx);
        if (typed == null) {
            TrackCommandMessages.refused(ctx, "stance.needed");
            return null;
        }
        TrackStanceAsset stance = TrackStanceConfig.getInstance().live(typed);
        if (stance == null) {
            TrackCommandMessages.refused(ctx, "stance.unknown", typed);
        }
        return stance;
    }
}
